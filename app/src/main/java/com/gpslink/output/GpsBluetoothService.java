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

import android.Manifest;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

public class GpsBluetoothService extends Service {

    public static final String ACTION_STOP = "com.gpslink.output.STOP";
    public static final String ACTION_STOPPED = "com.gpslink.output.STOPPED";
    public static final String ACTION_BT_ERROR = "com.gpslink.output.BT_ERROR";

    private static final String CHANNEL_ID = "gpslink_fg_channel";
    private static final String OLD_CHANNEL_ID = "gpslink_channel";
    private static final int NOTIF_ID = 1;
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final IBinder binder = new LocalBinder();
    private volatile UiCallback uiCallback;

    private LocationManager locationManager;
    private BluetoothAdapter btAdapter;

    private volatile BluetoothServerSocket serverSocket;
    private volatile BluetoothSocket btSocket;
    private volatile OutputStream btOut;

    private final LinkedBlockingQueue<String> writeQueue = new LinkedBlockingQueue<>(100);
    private int droppedMessages = 0;
    private Thread writeThread;

    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // volatile so BT-Write thread sees the update immediately
    private volatile boolean running = false;
    private int satsUsed = 0;
    private int satsInView = 0;
    private boolean hasFix = false;

    public static class SatInfo {
        public final String prn;
        public final String gnss;
        public final String snr;
        public final String elev;
        public final String azim;
        public final boolean isUsed;

        public SatInfo(String prn, String gnss, String snr, String elev, String azim, boolean isUsed) {
            this.prn = prn;
            this.gnss = gnss;
            this.snr = snr;
            this.elev = elev;
            this.azim = azim;
            this.isUsed = isUsed;
        }
    }

    private volatile java.util.List<SatInfo> lastSatDetails = new java.util.ArrayList<>();

    // -------------------------------------------------------------------------
    // Interface
    // -------------------------------------------------------------------------

    public interface UiCallback {
        void onGpsUpdate(boolean hasFix, int satsUsed, int satsInView,
                double lat, double lon, double alt, float speed, java.util.List<SatInfo> satDetails);

        void onBluetoothStatus(String status, String deviceName, boolean connected);

        void onDroppedMessages(int count);

        void onNmeaLog(String message);

        void onServiceStateChanged(boolean isRunning);
    }

    public class LocalBinder extends Binder {
        public GpsBluetoothService getService() {
            return GpsBluetoothService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

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

            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(8 * 60 * 60 * 1000L);
            }

            running = true;

            handler.post(() -> {
                UiCallback cb = uiCallback;
                if (cb != null)
                    cb.onServiceStateChanged(true);
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
        if (!running)
            return;
        running = false;
        hasFix = false;
        satsUsed = 0;
        satsInView = 0;
        lastSatDetails = new java.util.ArrayList<>();
        stopGps();

        // Interrupt write thread first so it stops trying to write
        if (writeThread != null) {
            writeThread.interrupt();
            writeThread = null;
        }

        BluetoothServerSocket ss = serverSocket;
        serverSocket = null;
        closeSilently(ss);

        closeClientSocket();

        if (wakeLock != null && wakeLock.isHeld())
            wakeLock.release();

        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }

        handler.post(() -> {
            UiCallback cb = uiCallback;
            if (cb != null) {
                cb.onBluetoothStatus("Server Stopped", null, false);
                cb.onGpsUpdate(false, 0, 0, 0, 0, 0, 0, new java.util.ArrayList<>());
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

    private void startGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e("GPSLink", "ACCESS_FINE_LOCATION not granted — stopping service");
            stopSelf();
            return;
        }
        locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 500, 0f, locationListener);
        locationManager.registerGnssStatusCallback(gnssStatusCallback, handler);
        locationManager.addNmeaListener(nmeaListener, handler);
    }

    private void stopGps() {
        try {
            locationManager.removeUpdates(locationListener);
        } catch (Exception ignored) {
        }
        try {
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
        } catch (Exception ignored) {
        }
        try {
            locationManager.removeNmeaListener(nmeaListener);
        } catch (Exception ignored) {
        }
    }

    private volatile double lastLat = 0.0;
    private volatile double lastLon = 0.0;
    private volatile double lastAltitude = 0.0;
    private volatile double lastEllipsoidAltitude = 0.0;
    private volatile float lastSpeed = 0.0f;
    private volatile float lastBearing = 0.0f;
    private volatile boolean lastBearingValid = false;

    private static final int NMEA_LOG_RING_SIZE = 30;
    private final java.util.ArrayDeque<String> nmeaLogRing = new java.util.ArrayDeque<>(NMEA_LOG_RING_SIZE);

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location loc) {
            hasFix = true;

            double alt = loc.getAltitude();
            lastEllipsoidAltitude = loc.getAltitude();
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
                lastBearingValid = true;
            } else {
                lastBearing = 0.0f;
                lastBearingValid = false;
            }

            UiCallback cb = uiCallback;
            if (cb != null) {
                cb.onGpsUpdate(true, satsUsed, satsInView,
                        loc.getLatitude(), loc.getLongitude(),
                        alt, loc.getSpeed(), lastSatDetails);
            }
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
            hasFix = false;
        }
    };

    private final GnssStatus.Callback gnssStatusCallback = new GnssStatus.Callback() {
        @Override
        public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
            int inView = status.getSatelliteCount();
            int used = 0;
            java.util.List<SatInfo> list = new java.util.ArrayList<>();
            for (int i = 0; i < inView; i++) {
                boolean isUsed = status.usedInFix(i);
                if (isUsed)
                    used++;
                String prn = String.format(java.util.Locale.US, "%03d", status.getSvid(i));
                String gnss = getConstellationName(status.getConstellationType(i));
                String snr = String.format(java.util.Locale.US, "%.0f", status.getCn0DbHz(i));
                String elev = String.format(java.util.Locale.US, "%.0f", status.getElevationDegrees(i));
                String azim = String.format(java.util.Locale.US, "%.0f", status.getAzimuthDegrees(i));
                list.add(new SatInfo(prn, gnss, snr, elev, azim, isUsed));
            }
            satsInView = inView;
            satsUsed = used;
            lastSatDetails = list;

            if (used == 0) {
                hasFix = false;
            }

            UiCallback cb = uiCallback;
            if (cb != null) {
                cb.onGpsUpdate(hasFix, satsUsed, satsInView, lastLat, lastLon, lastAltitude, lastSpeed, lastSatDetails);
            }
        }

        @Override
        public void onFirstFix(int ttffMillis) {
            hasFix = true;
        }

        @Override
        public void onStopped() {
            hasFix = false;
        }
    };

    private String getConstellationName(int type) {
        switch (type) {
            case GnssStatus.CONSTELLATION_GPS:
                return "GPS";
            case GnssStatus.CONSTELLATION_SBAS:
                return "SBAS";
            case GnssStatus.CONSTELLATION_GLONASS:
                return "GLO";
            case GnssStatus.CONSTELLATION_QZSS:
                return "QZSS";
            case GnssStatus.CONSTELLATION_BEIDOU:
                return "BDS";
            case GnssStatus.CONSTELLATION_GALILEO:
                return "GAL";
            case GnssStatus.CONSTELLATION_IRNSS:
                return "IRNSS";
            default:
                return "UNK";
        }
    }

    private final android.location.OnNmeaMessageListener nmeaListener = (message, timestamp) -> {
        if (!running || message == null || message.length() < 6 || !message.startsWith("$"))
            return;

        int commaIndex = message.indexOf(',');
        if (commaIndex == -1)
            return;

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

        if (!writeQueue.offer(finalMessage)) {
            writeQueue.poll();
            droppedMessages++;
            UiCallback dcb = uiCallback;
            if (dcb != null) {
                int count = droppedMessages;
                handler.post(() -> {
                    UiCallback ic = uiCallback;
                    if (ic != null)
                        ic.onDroppedMessages(count);
                });
            }
        }
        String trimmedForLog = finalMessage.trim();
        synchronized (nmeaLogRing) {
            if (nmeaLogRing.size() >= NMEA_LOG_RING_SIZE)
                nmeaLogRing.pollFirst();
            nmeaLogRing.addLast(trimmedForLog);
        }
        UiCallback cb = uiCallback;
        if (cb != null) {
            handler.post(() -> {
                UiCallback inner = uiCallback;
                if (inner != null)
                    inner.onNmeaLog(trimmedForLog);
            });
        }
    };

    private String joinAndChecksum(String[] parts) {
        String joined = String.join(",", parts);
        int xor = 0;
        for (char c : joined.toCharArray())
            xor ^= c;
        return "$" + joined + "*" + String.format(java.util.Locale.US, "%02X", xor);
    }

    private String overrideGgaAltitude(String message, double altitude) {
        if (Double.isNaN(altitude))
            return message;
        try {
            int starIdx = message.lastIndexOf('*');
            if (starIdx == -1)
                return message;

            String core = message.substring(0, starIdx);
            String[] parts = core.split(",", -1);
            if (parts.length < 10)
                return message;

            if (parts.length > 6 && "0".equals(parts[6]))
                return message;

            parts[9] = String.format(java.util.Locale.US, "%.1f", altitude);
            if (parts.length > 10 && parts[10].isEmpty())
                parts[10] = "M";

            if (parts.length > 11) {
                double geoidSep = lastEllipsoidAltitude - altitude;
                parts[11] = String.format(java.util.Locale.US, "%.1f", geoidSep);
                if (parts.length > 12 && parts[12].isEmpty())
                    parts[12] = "M";
            }

            // Strip leading '$' before recomputing checksum
            parts[0] = parts[0].substring(1);
            return joinAndChecksum(parts) + "\r\n";
        } catch (Exception e) {
            return message;
        }
    }

    private String overrideRmcSpeedAndCourse(String message, float speedMps, float bearing) {
        if (Float.isNaN(speedMps) || Float.isNaN(bearing))
            return message;
        try {
            int starIdx = message.lastIndexOf('*');
            if (starIdx == -1)
                return message;

            String core = message.substring(0, starIdx);
            String[] parts = core.split(",", -1);
            if (parts.length < 10)
                return message;

            if (parts.length > 2 && "V".equals(parts[2]))
                return message;

            double speedKnots = speedMps * 1.943844;
            parts[7] = String.format(java.util.Locale.US, "%.1f", speedKnots);
            parts[8] = lastBearingValid ? String.format(java.util.Locale.US, "%.1f", bearing) : "";

            parts[0] = parts[0].substring(1);
            return joinAndChecksum(parts) + "\r\n";
        } catch (Exception e) {
            return message;
        }
    }

    private String overrideVtgSpeedAndCourse(String message, float speedMps, float bearing) {
        if (Float.isNaN(speedMps) || Float.isNaN(bearing))
            return message;
        try {
            int starIdx = message.lastIndexOf('*');
            if (starIdx == -1)
                return message;

            String core = message.substring(0, starIdx);
            String[] parts = core.split(",", -1);
            if (parts.length < 8)
                return message;

            if (lastBearingValid) {
                parts[1] = String.format(java.util.Locale.US, "%.1f", bearing);
                if (parts.length > 2 && parts[2].isEmpty())
                    parts[2] = "T";
            } else {
                parts[1] = "";
                if (parts.length > 2)
                    parts[2] = "";
            }

            double speedKnots = speedMps * 1.943844;
            parts[5] = String.format(java.util.Locale.US, "%.1f", speedKnots);
            if (parts.length > 6 && parts[6].isEmpty())
                parts[6] = "N";

            if (parts.length > 7) {
                double speedKmh = speedMps * 3.6;
                parts[7] = String.format(java.util.Locale.US, "%.1f", speedKmh);
                if (parts.length > 8 && parts[8].isEmpty())
                    parts[8] = "K";
            }

            parts[0] = parts[0].substring(1);
            return joinAndChecksum(parts) + "\r\n";
        } catch (Exception e) {
            return message;
        }
    }

    // -------------------------------------------------------------------------
    // Bluetooth Server
    // -------------------------------------------------------------------------

    private void startServer() {
        if (btAdapter == null)
            return;
        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e("GPSLink", "BLUETOOTH_CONNECT not granted — stopping service");
            stopSelf();
            return;
        }
        if (!btAdapter.isEnabled()) {
            handler.post(() -> notifyBtStatus("Bluetooth Disabled", false));
            return;
        }

        new Thread(() -> {
            BluetoothServerSocket localServer;
            try {
                localServer = btAdapter.listenUsingRfcommWithServiceRecord(
                        "GPSLinkServer", SPP_UUID);
                serverSocket = localServer;
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
                    btOut = socket.getOutputStream();

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
                if (!running)
                    return;
                // Exponential back-off retry (max 5 attempts)
                int maxRetries = 5;
                for (int attempt = 0; attempt < maxRetries && running; attempt++) {
                    long delay = (1L << attempt) * 1000L; // 1s, 2s, 4s, 8s, 16s
                    int a = attempt + 1;
                    handler.post(() -> notifyBtStatus("Retry " + a + "/" + maxRetries + "...", false));
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        break;
                    }
                    if (!running)
                        break;
                    try {
                        closeSilently(serverSocket);
                        localServer = btAdapter.listenUsingRfcommWithServiceRecord(
                                "GPSLinkServer", SPP_UUID);
                        serverSocket = localServer;
                        // Success — re-enter accept loop via recursive call
                        BluetoothServerSocket resumeServer = localServer;
                        acceptLoop(resumeServer);
                        return;
                    } catch (IOException retryEx) {
                        // continue to next attempt
                    }
                }
                // All retries exhausted
                if (running) {
                    handler.post(() -> {
                        stopInternal();
                        Intent errorIntent = new Intent(ACTION_BT_ERROR);
                        errorIntent.setPackage(getPackageName());
                        sendBroadcast(errorIntent);
                    });
                }
            }
        }, "BT-Server").start();
    }

    private void acceptLoop(BluetoothServerSocket localServer) {
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
                btOut = socket.getOutputStream();

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
            if (running)
                handler.post(() -> notifyBtStatus("Server stopped", false));
        }
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
        btOut = null;
        btSocket = null;
        if (socket != null)
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        writeQueue.clear();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String getDeviceName(BluetoothDevice device) {
        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e("GPSLink", "BLUETOOTH_CONNECT not granted for getDeviceName");
            return device.getAddress();
        }
        try {
            String n = device.getName();
            return (n != null) ? n : device.getAddress();
        } catch (Exception e) {
            return device.getAddress();
        }
    }

    private void notifyBtStatus(String status, boolean connected) {
        UiCallback cb = uiCallback;
        if (cb == null)
            return;
        BluetoothSocket socket = btSocket; // atomic snapshot
        String name = null;
        if (socket != null) {
            try {
                name = getDeviceName(socket.getRemoteDevice());
            } catch (Exception ignored) {
            }
        }
        cb.onBluetoothStatus(status, name, connected);
    }

    private void notifyBtStatusConnected(String name) {
        UiCallback cb = uiCallback;
        if (cb != null)
            cb.onBluetoothStatus("Connected", name, true);
    }

    private void closeSilently(Object sock) {
        if (sock == null)
            return;
        try {
            if (sock instanceof BluetoothSocket)
                ((BluetoothSocket) sock).close();
            else if (sock instanceof BluetoothServerSocket)
                ((BluetoothServerSocket) sock).close();
        } catch (IOException ignored) {
        }
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
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
                .setContentTitle("GPSLink Output — Server Active")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(contentIntent)
                .addAction(android.R.drawable.ic_media_pause, "Stop Server", stopPi)
                .setOngoing(true)
                .setAutoCancel(false)
                .setShowWhen(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    private void createNotificationChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        // Delete the old low-importance channel if it exists (cached by OS)
        nm.deleteNotificationChannel(OLD_CHANNEL_ID);

        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "GPSLink Server", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("GPS server is running");
        ch.setShowBadge(true);
        nm.createNotificationChannel(ch);
    }

    public void setUiCallback(UiCallback cb) {
        uiCallback = cb;
    }

    public boolean isRunning() {
        return running;
    }

    public java.util.List<String> getRecentNmea() {
        synchronized (nmeaLogRing) {
            return new java.util.ArrayList<>(nmeaLogRing);
        }
    }
}
