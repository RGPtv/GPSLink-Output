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

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location loc) {
            hasFix = true;
            UiCallback cb = uiCallback;
            if (cb != null) {
                cb.onGpsUpdate(true, satsUsed, satsInView,
                        loc.getLatitude(), loc.getLongitude(),
                        loc.getAltitude(), loc.getSpeed());
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
        }
        @Override
        public void onFirstFix(int ttffMillis) { hasFix = true; }
    };

    private final android.location.OnNmeaMessageListener nmeaListener = (message, timestamp) -> {
        if (!running) return;
        while (!writeQueue.offer(message)) writeQueue.poll(); // drop oldest on overflow
        UiCallback cb = uiCallback;
        if (cb != null) {
            handler.post(() -> {
                UiCallback inner = uiCallback;
                if (inner != null) inner.onNmeaLog(message);
            });
        }
    };

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
                    handler.post(() -> notifyBtStatus("Waiting for connection...", false));
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
        handler.post(() -> notifyBtStatus("Client Disconnected", false));
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
