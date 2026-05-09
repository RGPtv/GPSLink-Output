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
import android.content.Context;
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

    public static final String ACTION_STOP = "com.gpslink.output.STOP";
    public static final String ACTION_STOPPED = "com.gpslink.output.STOPPED";
    public static final String EXTRA_DEVICE_ADDRESS = "device_address";

    private static final String CHANNEL_ID = "gpslink_channel";
    private static final int NOTIF_ID = 1;
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final long RECONNECT_DELAY_MS = 3000;

    private final IBinder binder = new LocalBinder();
    private volatile UiCallback uiCallback;

    private LocationManager locationManager;
    private BluetoothAdapter btAdapter;
    private volatile BluetoothServerSocket serverSocket;
    private volatile BluetoothSocket btSocket;
    private volatile OutputStream btOut;
    private final LinkedBlockingQueue<String> writeQueue = new LinkedBlockingQueue<>(100);
    private Thread writeThread;

    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean running = false;
    private boolean connecting = false;
    private int satsUsed = 0;
    private int satsInView = 0;
    private boolean hasFix = false;

    private final byte[] writeBuffer = new byte[256];

    public interface UiCallback {
        void onGpsUpdate(boolean hasFix, int satsUsed, int satsInView,
                         double lat, double lon, double alt, float speed);
        void onBluetoothStatus(String status, String deviceName, boolean connected);
        void onNmeaLog(String message);
    }

    public class LocalBinder extends Binder {
        public GpsBluetoothService getService() { return GpsBluetoothService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        btAdapter = bm != null ? bm.getAdapter() : null;
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GPSLink:WakeLock");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!running) {
            startForeground(NOTIF_ID, buildNotification("Waiting for connection..."));
            if (wakeLock != null) wakeLock.acquire(10 * 60 * 60 * 1000L);
            running = true;
            startGps();
            startWriteThread();
            startServer();
        }

        return START_STICKY;
    }

    @SuppressWarnings("MissingPermission")
    private void startGps() {
        locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 500, 0f, locationListener);
        locationManager.registerGnssStatusCallback(gnssStatusCallback, handler);
        locationManager.addNmeaListener(nmeaListener, handler);
    }

    private void stopGps() {
        try { locationManager.removeUpdates(locationListener); } catch (Exception ignored) {}
        try { locationManager.unregisterGnssStatusCallback(gnssStatusCallback); } catch (Exception ignored) {}
        try { locationManager.removeNmeaListener(nmeaListener); } catch (Exception ignored) {}
    }

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location loc) {
            hasFix = true;
            if (uiCallback != null) {
                uiCallback.onGpsUpdate(true, satsUsed, satsInView,
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
            int used = 0;
            for (int i = 0; i < inView; i++) {
                if (status.usedInFix(i)) used++;
            }
            satsInView = inView;
            satsUsed = used;
        }

        @Override
        public void onFirstFix(int ttffMillis) { hasFix = true; }
    };

    private final android.location.OnNmeaMessageListener nmeaListener = (message, timestamp) -> {
        if (!running) return;
        while (!writeQueue.offer(message)) {
            writeQueue.poll();
        }
        
        UiCallback cb = uiCallback;
        if (cb != null) {
            handler.post(() -> {
                UiCallback innerCb = uiCallback;
                if (innerCb != null) innerCb.onNmeaLog(message);
            });
        }
    };

    @SuppressWarnings("MissingPermission")
    private void startServer() {
        if (btAdapter == null) return;
        
        if (!btAdapter.isEnabled()) {
             handler.post(() -> notifyBtStatus("Bluetooth Disabled", false));
             return;
        }

        new Thread(() -> {
            try {
                serverSocket = btAdapter.listenUsingRfcommWithServiceRecord("GPSLinkServer", SPP_UUID);
                while (running) {
                    handler.post(() -> notifyBtStatus("Waiting for connection...", false));
                    BluetoothSocket socket = serverSocket.accept();
                    if (socket != null) {
                        closeClientSocket(); // Close previous client if any
                        btSocket = socket;
                        btOut = socket.getOutputStream();
                        String name = getDeviceName(socket.getRemoteDevice());
                        handler.post(() -> {
                            updateNotification("Connected: " + name);
                            notifyBtStatusConnected(name);
                        });
                    }
                }
            } catch (IOException e) {
                if (running) {
                    handler.post(() -> notifyBtStatus("Server stopped", false));
                }
            }
        }, "BT-Server").start();
    }

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
        btOut = null;
        btSocket = null;
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
        }
        writeQueue.clear();
    }

    @SuppressWarnings("MissingPermission")
    private String getDeviceName(BluetoothDevice device) {
        try {
            String n = device.getName();
            return n != null ? n : device.getAddress();
        } catch (Exception e) {
            return device.getAddress();
        }
    }

    private void notifyBtStatus(String status, boolean connected) {
        UiCallback cb = uiCallback;
        if (cb != null) {
            BluetoothSocket socket = btSocket;
            cb.onBluetoothStatus(status,
                    socket != null ? getDeviceName(socket.getRemoteDevice()) : null,
                    connected);
        }
    }

    private void notifyBtStatusConnected(String name) {
        UiCallback cb = uiCallback;
        if (cb != null) {
            cb.onBluetoothStatus("Connected", name, true);
        }
    }

    private void closeSilently(Object sock) {
        if (sock == null) return;
        try {
            if (sock instanceof BluetoothSocket) ((BluetoothSocket) sock).close();
            else if (sock instanceof BluetoothServerSocket) ((BluetoothServerSocket) sock).close();
        } catch (IOException ignored) {}
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        Intent pi = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, pi,
                PendingIntent.FLAG_IMMUTABLE);

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
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
    }


    public void setUiCallback(UiCallback cb) {
        uiCallback = cb;
    }

    public boolean isRunning() { return running; }

    @Override
    public void onDestroy() {
        running = false;
        stopGps();
        handler.removeCallbacksAndMessages(null);
        if (writeThread != null) writeThread.interrupt();
        closeSilently(serverSocket);
        closeClientSocket();
        serverSocket = null;
        writeThread = null;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        Intent stopIntent = new Intent(ACTION_STOPPED);
        stopIntent.setPackage(getPackageName());
        sendBroadcast(stopIntent);
        super.onDestroy();
    }
}
