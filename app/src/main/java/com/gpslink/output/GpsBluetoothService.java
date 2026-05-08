package com.gpslink.output;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
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
    private BluetoothSocket btSocket;
    private OutputStream btOut;
    private BluetoothDevice targetDevice;

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
            String addr = intent != null ? intent.getStringExtra(EXTRA_DEVICE_ADDRESS) : null;
            if (addr == null) addr = Prefs.getLastDevice(this);
            if (addr != null && btAdapter != null) {
                targetDevice = btAdapter.getRemoteDevice(addr);
            }
            startForeground(NOTIF_ID, buildNotification("Starting..."));
            wakeLock.acquire(10 * 60 * 60 * 1000L);
            running = true;
            startGps();
            connectBluetooth();
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
        if (!running || btOut == null) return;
        try {
            byte[] bytes = message.getBytes();
            btOut.write(bytes);
        } catch (IOException e) {
            handleBluetoothDisconnect();
        }
    };

    private void connectBluetooth() {
        if (connecting || targetDevice == null) return;
        connecting = true;

        notifyBtStatus("Connecting...", false);

        new Thread(() -> {
            BluetoothSocket sock = null;
            try {
                btAdapter.cancelDiscovery();
                sock = targetDevice.createRfcommSocketToServiceRecord(SPP_UUID);
                sock.connect();
                btSocket = sock;
                btOut = sock.getOutputStream();
                connecting = false;
                String name = getDeviceName(targetDevice);
                Prefs.saveLastDevice(GpsBluetoothService.this, targetDevice.getAddress());
                handler.post(() -> {
                    updateNotification("Connected: " + name);
                    notifyBtStatusConnected(name);
                });
            } catch (Exception e) {
                connecting = false;
                closeSilently(sock);
                btSocket = null;
                btOut = null;
                handler.postDelayed(this::connectBluetooth, RECONNECT_DELAY_MS);
                handler.post(() -> notifyBtStatus("Reconnecting...", false));
            }
        }, "BT-Connect").start();
    }

    private void handleBluetoothDisconnect() {
        closeSilently(btSocket);
        btSocket = null;
        btOut = null;
        handler.post(() -> {
            notifyBtStatus("Disconnected", false);
            if (running) {
                handler.postDelayed(this::connectBluetooth, RECONNECT_DELAY_MS);
            }
        });
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
            uiCallback.onBluetoothStatus(status,
                    targetDevice != null ? getDeviceName(targetDevice) : null,
                    connected);
        }
    }

    private void notifyBtStatusConnected(String name) {
        if (uiCallback != null) {
            uiCallback.onBluetoothStatus("Connected", name, true);
        }
    }

    private void closeSilently(BluetoothSocket sock) {
        if (sock != null) try { sock.close(); } catch (IOException ignored) {}
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

    public void setTargetDevice(BluetoothDevice device) {
        targetDevice = device;
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
        closeSilently(btSocket);
        btSocket = null;
        btOut = null;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        sendBroadcast(new Intent(ACTION_STOPPED));
        super.onDestroy();
    }
}
