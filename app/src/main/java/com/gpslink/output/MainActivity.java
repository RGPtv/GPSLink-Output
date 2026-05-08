package com.gpslink.output;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMISSIONS = 1;

    private TextView tvFix, tvSats, tvLat, tvLon, tvAlt, tvSpeed, tvBtStatus, tvBtDevice, tvLog;
    private Button btnToggle;

    private GpsBluetoothService service;
    private boolean bound = false;
    private boolean running = false;

    private final List<String> nmeaLogs = new ArrayList<>();
    private static final int MAX_LOGS = 20;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((GpsBluetoothService.LocalBinder) binder).getService();
            bound = true;
            service.setUiCallback(uiCallback);
            running = service.isRunning();
            updateToggleButton();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
        }
    };

    private final GpsBluetoothService.UiCallback uiCallback = new GpsBluetoothService.UiCallback() {
        @Override
        public void onGpsUpdate(boolean hasFix, int satsUsed, int satsInView,
                                double lat, double lon, double alt, float speed) {
            runOnUiThread(() -> {
                tvFix.setText(hasFix ? "Fix" : "No Fix");
                tvFix.setTextColor(getColor(hasFix ? R.color.green : R.color.red));
                tvSats.setText(satsUsed + " / " + satsInView);
                if (hasFix) {
                    tvLat.setText(String.format("%.6f", lat));
                    tvLon.setText(String.format("%.6f", lon));
                    tvAlt.setText(String.format("%.1f", alt));
                    tvSpeed.setText(String.format("%.1f", speed * 3.6f));
                }
            });
        }

        @Override
        public void onBluetoothStatus(String status, String deviceName, boolean connected) {
            runOnUiThread(() -> {
                tvBtStatus.setText(status.toUpperCase());
                tvBtStatus.setTextColor(getColor(connected ? R.color.accent : R.color.text_secondary));
                tvBtDevice.setText(deviceName != null ? deviceName : "None");
            });
        }

        @Override
        public void onNmeaLog(String message) {
            runOnUiThread(() -> {
                nmeaLogs.add(message.trim());
                if (nmeaLogs.size() > MAX_LOGS) nmeaLogs.remove(0);
                
                StringBuilder sb = new StringBuilder();
                for (String line : nmeaLogs) {
                    sb.append(line).append("\n");
                }
                tvLog.setText(sb.toString());
            });
        }
    };

    private final BroadcastReceiver serviceStopReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            running = false;
            updateToggleButton();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvFix = findViewById(R.id.tvFix);
        tvSats = findViewById(R.id.tvSats);
        tvLat = findViewById(R.id.tvLat);
        tvLon = findViewById(R.id.tvLon);
        tvAlt = findViewById(R.id.tvAlt);
        tvSpeed = findViewById(R.id.tvSpeed);
        tvBtStatus = findViewById(R.id.tvBtStatus);
        tvBtDevice = findViewById(R.id.tvBtDevice);
        tvLog = findViewById(R.id.tvLog);
        btnToggle = findViewById(R.id.btnToggle);

        btnToggle.setOnClickListener(v -> {
            if (!running) startService();
            else stopServiceAction();
        });

        registerReceiver(serviceStopReceiver,
                new IntentFilter(GpsBluetoothService.ACTION_STOPPED),
                RECEIVER_NOT_EXPORTED);

        checkPermissions();
    }

    private void checkPermissions() {
        List<String> needed = new ArrayList<>();
        String[] perms = Build.VERSION.SDK_INT >= 31
                ? new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN}
                : new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN};

        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) needed.add(p);
        }

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQ_PERMISSIONS);
        } else {
            onPermissionsGranted();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_PERMISSIONS) {
            for (int r : results) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show();
                    return;
                }
            }
            onPermissionsGranted();
        }
    }

    private void onPermissionsGranted() {
        bindToService();
    }

    private void bindToService() {
        Intent intent = new Intent(this, GpsBluetoothService.class);
        bindService(intent, conn, BIND_AUTO_CREATE);
    }

    private void startService() {
        Intent intent = new Intent(this, GpsBluetoothService.class);
        startForegroundService(intent);

        if (!bound) bindToService();
        running = true;
        updateToggleButton();
    }

    private void stopServiceAction() {
        Intent intent = new Intent(this, GpsBluetoothService.class);
        intent.setAction(GpsBluetoothService.ACTION_STOP);
        startService(intent);
        running = false;
        updateToggleButton();
    }

    private void updateToggleButton() {
        btnToggle.setText(running ? "SHUTDOWN SERVER" : "INITIALIZE SERVER");
        btnToggle.setBackgroundTintList(getColorStateList(running ? R.color.red : R.color.green));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (bound && service != null) {
            service.setUiCallback(uiCallback);
            running = service.isRunning();
            updateToggleButton();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (bound && service != null) service.setUiCallback(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(serviceStopReceiver);
        if (bound) {
            unbindService(conn);
            bound = false;
        }
    }
}
