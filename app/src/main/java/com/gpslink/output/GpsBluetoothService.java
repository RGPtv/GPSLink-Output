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
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

public class GpsBluetoothService extends Service {

    public static final String ACTION_STOP = "com.gpslink.output.STOP";
    public static final String ACTION_STOPPED = "com.gpslink.output.STOPPED";
    public static final String EXTRA_DEVICE_ADDRESS = "device_address";

    private static final String CHANNEL_ID = "gpslink_channel";
    private static final int NOTIF_ID = 1;
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final long RECONNECT_DELAY_MS = 3000;

    private final IBinder binder = new LocalBinder();
    private UiCallback uiCallback;

    private LocationManager locationManager;
    private BluetoothAdapter btAdapter;
    private final AtomicReference<BluetoothServerSocket> serverSocketRef = new AtomicReference<>();
    private final AtomicReference<BluetoothSocket> clientSocketRef = new AtomicReference<>();
    private final AtomicReference<OutputStream> outputStreamRef = new AtomicReference<>();
    
    private final LinkedBlockingQueue<String> nmeaQueue = new LinkedBlockingQueue<>(100);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong dataSentCount = new AtomicLong(0);

    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private volatile int satsUsed = 0;
    private volatile int satsInView = 0;
    private volatile boolean hasFix = false;

    private Thread serverThread;
    private Thread transmitterThread;

    public interface UiCallback {
        void onGpsUpdate(boolean hasFix, int satsUsed, int satsInView,
                         double lat, double lon, double alt, float speed);
        void onBluetoothStatus(String status, String deviceName, boolean connected, long dataSent);
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

        if (running.compareAndSet(false, true)) {
            Notification notif = buildNotification("Waiting for connection...");
            if (Build.VERSION.SDK_INT >= 34) {
                // Using literal 8 for ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                startForeground(NOTIF_ID, notif, 8);
            } else {
                startForeground(NOTIF_ID, notif);
            }
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(10 * 60 * 60 * 1000L);
            dataSentCount.set(0);
            startGps();
            startServer();
            startTransmitter();
        }

        return START_STICKY;
    }

    @SuppressWarnings("MissingPermission")
    private void startGps() {
        try {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 500, 0f, locationListener);
        } catch (Exception e) {
            notifyBtStatus("GPS Provider Error", false);
        }

        try {
            locationManager.registerGnssStatusCallback(gnssStatusCallback, handler);
        } catch (Exception e) {
            // Some low-end devices don't support GNSS status
        }

        try {
            locationManager.addNmeaListener(nmeaListener, handler);
        } catch (Exception e) {
            notifyBtStatus("NMEA Listener Error", false);
        }
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
        if (!running.get()) return;
        nmeaQueue.offer(message);
    };

    private void startTransmitter() {
        transmitterThread = new Thread(() -> {
            while (running.get()) {
                try {
                    String message = nmeaQueue.poll(500, TimeUnit.MILLISECONDS);
                    if (message == null) continue;

                    OutputStream os = outputStreamRef.get();
                    if (os != null) {
                        try {
                            os.write(message.getBytes());
                            os.flush();
                            long count = dataSentCount.incrementAndGet();
                            if (count % 10 == 0) { // Notify UI every 10 messages
                                handler.post(() -> notifyDataSent(count));
                            }
                        } catch (IOException e) {
                            handleBluetoothDisconnect();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "BT-Transmitter");
        transmitterThread.start();
    }

    private void startServer() {
        if (btAdapter == null) return;

        serverThread = new Thread(() -> {
            try {
                BluetoothServerSocket ss = btAdapter.listenUsingRfcommWithServiceRecord("GPSLinkServer", SPP_UUID);
                serverSocketRef.set(ss);
                while (running.get()) {
                    handler.post(() -> notifyBtStatus("Waiting for connection...", false));
                    BluetoothSocket socket = ss.accept();
                    if (socket != null) {
                        disconnectClient(); // Close previous client if any
                        clientSocketRef.set(socket);
                        outputStreamRef.set(socket.getOutputStream());
                        String name = getDeviceName(socket.getRemoteDevice());
                        handler.post(() -> {
                            updateNotification("Connected: " + name);
                            notifyBtStatusConnected(name);
                        });
                    }
                }
            } catch (IOException e) {
                if (running.get()) {
                    handler.post(() -> notifyBtStatus("Server stopped", false));
                }
            }
        }, "BT-Server");
        serverThread.start();
    }

    private void disconnectClient() {
        OutputStream os = outputStreamRef.getAndSet(null);
        if (os != null) try { os.close(); } catch (IOException ignored) {}
        
        BluetoothSocket sock = clientSocketRef.getAndSet(null);
        if (sock != null) try { sock.close(); } catch (IOException ignored) {}
    }

    private void handleBluetoothDisconnect() {
        disconnectClient();
        handler.post(() -> notifyBtStatus("Client Disconnected", false));
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
        if (uiCallback != null) {
            BluetoothSocket socket = clientSocketRef.get();
            uiCallback.onBluetoothStatus(status,
                    socket != null ? getDeviceName(socket.getRemoteDevice()) : null,
                    connected, dataSentCount.get());
        }
    }

    private void notifyDataSent(long count) {
        if (uiCallback != null) {
            BluetoothSocket socket = clientSocketRef.get();
            uiCallback.onBluetoothStatus(socket != null ? "Streaming" : "Waiting",
                    socket != null ? getDeviceName(socket.getRemoteDevice()) : null,
                    socket != null, count);
        }
    }

    private void notifyBtStatusConnected(String name) {
        if (uiCallback != null) {
            uiCallback.onBluetoothStatus("Connected", name, true, dataSentCount.get());
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
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(contentIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
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

    public boolean isRunning() { return running.get(); }

    @Override
    public void onDestroy() {
        running.set(false);
        stopGps();
        handler.removeCallbacksAndMessages(null);
        
        BluetoothServerSocket ss = serverSocketRef.getAndSet(null);
        if (ss != null) try { ss.close(); } catch (IOException ignored) {}
        
        disconnectClient();
        
        if (transmitterThread != null) transmitterThread.interrupt();
        if (serverThread != null) serverThread.interrupt();
        
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        sendBroadcast(new Intent(ACTION_STOPPED));
        super.onDestroy();
    }
}
