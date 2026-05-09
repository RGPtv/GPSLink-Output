package com.gpslink.output;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

public class GpsBluetoothService extends Service {

    public static final String ACTION_STOP    = "com.gpslink.output.STOP";
    public static final String ACTION_STOPPED = "com.gpslink.output.STOPPED";
    public static final String EXTRA_DEVICE_ADDRESS = "device_address";

    private static final String CHANNEL_ID = "gpslink_channel";
    private static final int    NOTIF_ID   = 1;
    private static final UUID   SPP_UUID   =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final IBinder binder = new LocalBinder();
    private volatile UiCallback uiCallback;

    private LocationManager locationManager;
    private BluetoothAdapter btAdapter;

    private volatile BluetoothServerSocket serverSocket;
    private volatile BluetoothSocket       btSocket;
    private volatile OutputStream          btOut;

    private final LinkedBlockingQueue<String> writeQueue = new LinkedBlockingQueue<>(100);
    private Thread writeThread;

    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // volatile so BT-Write thread sees the update immediately
    private volatile boolean running = false;
    private int satsUsed   = 0;
    private int satsInView = 0;
    private boolean hasFix = false;

    // FIX #6: writeBuffer removed — was allocated but never used

    // -------------------------------------------------------------------------
    // Interface
    // -------------------------------------------------------------------------

    public interface UiCallback {
        void onGpsUpdate(boolean hasFix, int satsUsed, int satsInView,
                         double lat, double lon, double alt, float speed);
        void onBluetoothStatus(String status, String deviceName, boolean connected);
        void onNmeaLog(String message);
        void onServiceStateChanged(boolean isRunning);
    }

    public class LocalBinder extends Binder {
        public GpsBluetoothService getService() { return GpsBluetoothService.this; }
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        btAdapter = (bm != null) ? bm.getAdapter() : null;
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        // FIX #1: null-check PowerManager before calling newWakeLock
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GPSLink:WakeLock");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopInternal();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!running) {
            startForeground(NOTIF_ID, buildNotification("Waiting for connection..."));

            // FIX #1: guard isHeld(); use realistic 8-hour cap (was 10h, unguarded)
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(8 * 60 * 60 * 1000L);
            }

            running = true;
            
            handler.post(() -> {
                UiCallback cb = uiCallback;
                if (cb != null) cb.onServiceStateChanged(true);
            });
            
            startGps();
            startWriteThread();
            startServer();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopInternal();
        super.onDestroy();
    }

    private void stopInternal() {
        if (!running) return;
        running = false;
        hasFix = false;
        satsUsed = 0;
        satsInView = 0;
        stopGps();

        // Interrupt write thread first so it stops trying to write
        if (writeThread != null) {
            writeThread.interrupt();
            writeThread = null;
        }

        // FIX #3: null serverSocket field first (thread checks running flag),
        // then close so accept() unblocks cleanly without NPE in server thread
        BluetoothServerSocket ss = serverSocket;
        serverSocket = null;
        closeSilently(ss);

        closeClientSocket();

        // FIX #1: release WakeLock safely
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();

        stopForeground(true);

        handler.removeCallbacksAndMessages(null);
        handler.post(() -> {
            UiCallback cb = uiCallback;
            if (cb != null) {
                cb.onBluetoothStatus("Server Stopped", null, false);
                cb.onGpsUpdate(false, 0, 0, 0, 0, 0, 0);
                cb.onServiceStateChanged(false);
            }
        });

        Intent stopped = new Intent(ACTION_STOPPED);
        stopped.setPackage(getPackageName());
        sendBroadcast(stopped);
    }

    // -------------------------------------------------------------------------
    // GPS
    // -------------------------------------------------------------------------

    @SuppressWarnings("MissingPermission")
    private void startGps() {
        locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 500, 0f, locationListener);
        locationManager.registerGnssStatusCallback(gnssStatusCallback, handler);
        locationManager.addNmeaListener(nmeaListener, handler);
    }

    private void stopGps() {
        try { locationManager.removeUpdates(locationListener); }                   catch (Exception ignored) {}
        try { locationManager.unregisterGnssStatusCallback(gnssStatusCallback); }  catch (Exception ignored) {}
        try { locationManager.removeNmeaListener(nmeaListener); }                  catch (Exception ignored) {}
    }

    private volatile double lastLat = 0.0;
    private volatile double lastLon = 0.0;
    private volatile double lastAltitude = 0.0;
    private volatile float lastSpeed = 0.0f;
    private volatile float lastBearing = 0.0f;

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location loc) {
            hasFix = true;
            
            double alt = loc.getAltitude();
            if (Build.VERSION.SDK_INT >= 34) { // Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                if (loc.hasMslAltitude()) {
                    alt = loc.getMslAltitudeMeters();
                }
            }
            lastLat = loc.getLatitude();
            lastLon = loc.getLongitude();
            lastAltitude = alt;

            if (loc.hasSpeed()) {
                lastSpeed = loc.getSpeed();
            } else {
                lastSpeed = 0.0f;
            }
            if (loc.hasBearing()) {
                lastBearing = loc.getBearing();
            } else {
                lastBearing = 0.0f;
            }

            UiCallback cb = uiCallback;
            if (cb != null) {
                cb.onGpsUpdate(true, satsUsed, satsInView,
                        loc.getLatitude(), loc.getLongitude(),
                        alt, loc.getSpeed());
            }
        }
        @Override
        public void onProviderDisabled(@NonNull String provider) { hasFix = false; }
    };

    private final GnssStatus.Callback gnssStatusCallback = new GnssStatus.Callback() {
        @Override
        public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
            int inView = status.getSatelliteCount();
            int used   = 0;
            for (int i = 0; i < inView; i++) if (status.usedInFix(i)) used++;
            satsInView = inView;
            satsUsed   = used;
            
            UiCallback cb = uiCallback;
            if (cb != null) {
                // Run on UI thread if required, but callback wrapper in MainActivity handles runOnUiThread
                cb.onGpsUpdate(hasFix, satsUsed, satsInView, lastLat, lastLon, lastAltitude, lastSpeed);
            }
        }
        @Override
        public void onFirstFix(int ttffMillis) { hasFix = true; }
    };

    private final android.location.OnNmeaMessageListener nmeaListener = (message, timestamp) -> {
        if (!running || message == null || message.length() < 6 || !message.startsWith("$")) return;

        int commaIndex = message.indexOf(',');
        if (commaIndex == -1) return;

        String header = message.substring(0, commaIndex);
        if (!header.endsWith("RMC") && !header.endsWith("GGA") && 
            !header.endsWith("GSA") && !header.endsWith("GSV") &&
            !header.endsWith("VTG") && !header.endsWith("GLL") &&
            !header.endsWith("ZDA")) {
            return;
        }

        String finalMessage = message;
        if (header.endsWith("GGA")) {
            finalMessage = overrideGgaAltitude(message, lastAltitude);
        } else if (header.endsWith("RMC")) {
            finalMessage = overrideRmcSpeedAndCourse(message, lastSpeed, lastBearing);
        } else if (header.endsWith("VTG")) {
            finalMessage = overrideVtgSpeedAndCourse(message, lastSpeed, lastBearing);
        }

        // Strictly enforce \r\n line endings for NMEA compliance
        if (!finalMessage.endsWith("\r\n")) {
            if (finalMessage.endsWith("\n")) {
                finalMessage = finalMessage.substring(0, finalMessage.length() - 1) + "\r\n";
            } else {
                finalMessage += "\r\n";
            }
        }

        while (!writeQueue.offer(finalMessage)) writeQueue.poll(); // drop oldest on overflow
        UiCallback cb = uiCallback;
        if (cb != null) {
            String msgToLog = finalMessage;
            handler.post(() -> {
                UiCallback inner = uiCallback;
                if (inner != null) inner.onNmeaLog(msgToLog);
            });
        }
    };

    private String overrideGgaAltitude(String message, double altitude) {
        if (Double.isNaN(altitude)) return message;
        try {
            int starIdx = message.lastIndexOf('*');
            if (starIdx == -1) return message;
            
            String core = message.substring(0, starIdx);
            String[] parts = core.split(",", -1);
            if (parts.length < 10) return message;
            
            // Do not override if the GPS reports no fix (quality "0")
            if (parts.length > 6 && "0".equals(parts[6])) return message;
            
            // GGA altitude is in meters
            parts[9] = String.format(java.util.Locale.US, "%.1f", altitude);
            if (parts.length > 10 && parts[10].isEmpty()) parts[10] = "M";
            
            // Zero the geoid separation so the receiver doesn't miscalculate
            if (parts.length > 11) {
                parts[11] = "0.0";
                if (parts.length > 12 && parts[12].isEmpty()) parts[12] = "M";
            }
            
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                sb.append(parts[i]);
                if (i < parts.length - 1) sb.append(",");
            }
            
            String newCore = sb.toString();
            int checksum = 0;
            for (int i = 1; i < newCore.length(); i++) { // Skip '$'
                checksum ^= newCore.charAt(i);
            }
            
            return newCore + String.format(java.util.Locale.US, "*%02X\r\n", checksum);
        } catch (Exception e) {
            return message;
        }
    }

    private String overrideRmcSpeedAndCourse(String message, float speedMps, float bearing) {
        if (Float.isNaN(speedMps) || Float.isNaN(bearing)) return message;
        try {
            int starIdx = message.lastIndexOf('*');
            if (starIdx == -1) return message;
            
            String core = message.substring(0, starIdx);
            String[] parts = core.split(",", -1);
            if (parts.length < 10) return message;
            
            // Do not override if the GPS reports warning/no fix ("V")
            if (parts.length > 2 && "V".equals(parts[2])) return message;
            
            // Speed in knots (1 m/s = 1.943844 knots)
            double speedKnots = speedMps * 1.943844;
            parts[7] = String.format(java.util.Locale.US, "%.1f", speedKnots);
            
            // Track angle / Course in degrees
            parts[8] = String.format(java.util.Locale.US, "%.1f", bearing);
            
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                sb.append(parts[i]);
                if (i < parts.length - 1) sb.append(",");
            }
            
            String newCore = sb.toString();
            int checksum = 0;
            for (int i = 1; i < newCore.length(); i++) { // Skip '$'
                checksum ^= newCore.charAt(i);
            }
            
            return newCore + String.format(java.util.Locale.US, "*%02X\r\n", checksum);
        } catch (Exception e) {
            return message;
        }
    }

    private String overrideVtgSpeedAndCourse(String message, float speedMps, float bearing) {
        if (Float.isNaN(speedMps) || Float.isNaN(bearing)) return message;
        try {
            int starIdx = message.lastIndexOf('*');
            if (starIdx == -1) return message;
            
            String core = message.substring(0, starIdx);
            String[] parts = core.split(",", -1);
            if (parts.length < 8) return message;
            
            parts[1] = String.format(java.util.Locale.US, "%.1f", bearing);
            if (parts.length > 2 && parts[2].isEmpty()) parts[2] = "T";
            
            double speedKnots = speedMps * 1.943844;
            parts[5] = String.format(java.util.Locale.US, "%.1f", speedKnots);
            if (parts.length > 6 && parts[6].isEmpty()) parts[6] = "N";
            
            if (parts.length > 7) {
                double speedKmh = speedMps * 3.6;
                parts[7] = String.format(java.util.Locale.US, "%.1f", speedKmh);
                if (parts.length > 8 && parts[8].isEmpty()) parts[8] = "K";
            }
            
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                sb.append(parts[i]);
                if (i < parts.length - 1) sb.append(",");
            }
            
            String newCore = sb.toString();
            int checksum = 0;
            for (int i = 1; i < newCore.length(); i++) { // Skip '$'
                checksum ^= newCore.charAt(i);
            }
            
            return newCore + String.format(java.util.Locale.US, "*%02X\r\n", checksum);
        } catch (Exception e) {
            return message;
        }
    }

    // -------------------------------------------------------------------------
    // Bluetooth Server
    // -------------------------------------------------------------------------

    @SuppressWarnings("MissingPermission")
    private void startServer() {
        if (btAdapter == null) return;
        if (!btAdapter.isEnabled()) {
            handler.post(() -> notifyBtStatus("Bluetooth Disabled", false));
            return;
        }

        new Thread(() -> {
            // FIX #3: create and snapshot serverSocket inside thread to eliminate
            // the race where onDestroy() nulls the field before accept() reads it
            BluetoothServerSocket localServer;
            try {
                localServer  = btAdapter.listenUsingRfcommWithServiceRecord(
                        "GPSLinkServer", SPP_UUID);
                serverSocket = localServer; // publish ref so onDestroy can close it
            } catch (IOException e) {
                handler.post(() -> notifyBtStatus("BT listen failed", false));
                return;
            }

            try {
                while (running) {
                    if (btSocket == null) {
                        handler.post(() -> notifyBtStatus("Waiting for connection...", false));
                    }
                    BluetoothSocket socket = localServer.accept();
                    if (socket == null || !running) {
                        closeSilently(socket);
                        break;
                    }
                    closeClientSocket();
                    btSocket = socket;
                    btOut    = socket.getOutputStream();

                    // FIX #5: persist last connected device address for boot auto-start
                    Prefs.saveLastDevice(
                            GpsBluetoothService.this,
                            socket.getRemoteDevice().getAddress());

                    String name = getDeviceName(socket.getRemoteDevice());
                    handler.post(() -> {
                        updateNotification("Connected: " + name);
                        notifyBtStatusConnected(name);
                    });
                }
            } catch (IOException e) {
                if (running) handler.post(() -> notifyBtStatus("Server stopped", false));
            }
        }, "BT-Server").start();
    }

    // -------------------------------------------------------------------------
    // Bluetooth Write Thread
    // -------------------------------------------------------------------------

    private void startWriteThread() {
        writeThread = new Thread(() -> {
            while (running) {
                try {
                    String msg = writeQueue.take();
                    OutputStream out = btOut;
                    if (out != null) {
                        out.write(msg.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        out.flush();
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (IOException e) {
                    handleBluetoothDisconnect();
                }
            }
        }, "BT-Write");
        writeThread.start();
    }

    private void handleBluetoothDisconnect() {
        closeClientSocket();
        if (running) {
            handler.post(() -> notifyBtStatus("Waiting for connection...", false));
        }
    }

    private void closeClientSocket() {
        BluetoothSocket socket = btSocket;
        btOut    = null;
        btSocket = null;
        if (socket != null) try { socket.close(); } catch (IOException ignored) {}
        writeQueue.clear();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("MissingPermission")
    private String getDeviceName(BluetoothDevice device) {
        try {
            String n = device.getName();
            return (n != null) ? n : device.getAddress();
        } catch (Exception e) {
            return device.getAddress();
        }
    }

    // FIX #2: snapshot both uiCallback and btSocket to prevent TOCTOU NPE
    // across the BT-Write and main threads
    private void notifyBtStatus(String status, boolean connected) {
        UiCallback cb = uiCallback;
        if (cb == null) return;
        BluetoothSocket socket = btSocket; // atomic snapshot
        String name = null;
        if (socket != null) {
            try { name = getDeviceName(socket.getRemoteDevice()); }
            catch (Exception ignored) {}
        }
        cb.onBluetoothStatus(status, name, connected);
    }

    private void notifyBtStatusConnected(String name) {
        UiCallback cb = uiCallback;
        if (cb != null) cb.onBluetoothStatus("Connected", name, true);
    }

    private void closeSilently(Object sock) {
        if (sock == null) return;
        try {
            if (sock instanceof BluetoothSocket)            ((BluetoothSocket) sock).close();
            else if (sock instanceof BluetoothServerSocket) ((BluetoothServerSocket) sock).close();
        } catch (IOException ignored) {}
    }

    private void updateNotification(String text) {
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, GpsBluetoothService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GPSLink Output")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(contentIntent)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "GPS Streaming", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("GPS NMEA streaming over Bluetooth");
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .createNotificationChannel(ch);
    }

    public void setUiCallback(UiCallback cb) { uiCallback = cb; }
    public boolean isRunning() { return running; }
}
