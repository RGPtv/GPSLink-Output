package com.gpslink.output;

import android.Manifest;
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
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

// FIX #12: Removed unused imports: Spinner, ArrayAdapter, AdapterView, BluetoothAdapter,
//          BluetoothDevice, BluetoothManager, View

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMISSIONS = 1;

    private TextView tvFix, tvSats, tvLat, tvLon, tvAlt, tvSpeed,
                     tvBtStatus, tvBtDevice, tvLog;
    private Button btnToggle;

    private GpsBluetoothService service;
    private boolean bound = false;

    // FIX #11: 'running' is now derived exclusively from the service, not set
    // optimistically. Eliminated local boolean; all reads go through deriveRunning().

    private static final int MAX_LOGS = 20;
    // FIX #9: Use StringBuilder kept across calls; append-only, trim at MAX_LOGS
    private final StringBuilder logBuilder = new StringBuilder();
    private int logLineCount = 0;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((GpsBluetoothService.LocalBinder) binder).getService();
            bound = true;
            service.setUiCallback(uiCallback);
            updateToggleButton();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
            updateToggleButton();
        }
    };

    private final GpsBluetoothService.UiCallback uiCallback =
            new GpsBluetoothService.UiCallback() {

        @Override
        public void onGpsUpdate(boolean hasFix, int satsUsed, int satsInView,
                                double lat, double lon, double alt, float speed) {
            runOnUiThread(() -> {
                tvFix.setText(hasFix ? "Fix" : "No Fix");
                tvFix.setTextColor(ContextCompat.getColor(
                        MainActivity.this, hasFix ? R.color.green : R.color.red));
                tvSats.setText(satsUsed + " / " + satsInView);
                if (hasFix) {
                    tvLat.setText(String.format(java.util.Locale.US, "%.6f", lat));
                    tvLon.setText(String.format(java.util.Locale.US, "%.6f", lon));
                    tvAlt.setText(String.format(java.util.Locale.US, "%.1f", alt));
                    tvSpeed.setText(String.format(java.util.Locale.US, "%.1f", speed * 3.6f));
                }
            });
        }

        @Override
        public void onBluetoothStatus(String status, String deviceName, boolean connected) {
            runOnUiThread(() -> {
                tvBtStatus.setText(status.toUpperCase(java.util.Locale.US));
                tvBtStatus.setTextColor(ContextCompat.getColor(
                        MainActivity.this, connected ? R.color.accent : R.color.text_secondary));
                tvBtDevice.setText(deviceName != null ? deviceName : "None");
            });
        }

        @Override
        public void onNmeaLog(String message) {
            runOnUiThread(() -> {
                // FIX #9: O(1) append instead of O(n) full rebuild on every message
                String trimmed = message.trim();
                if (logLineCount >= MAX_LOGS) {
                    // Remove the first line
                    int nl = logBuilder.indexOf("\n");
                    if (nl >= 0) logBuilder.delete(0, nl + 1);
                    else logBuilder.setLength(0);
                    logLineCount--;
                }
                if (logBuilder.length() > 0) logBuilder.append('\n');
                logBuilder.append(trimmed);
                logLineCount++;
                tvLog.setText(logBuilder.toString());
            });
        }
    };

    // FIX #11: react to service stop via broadcast; derive state from service, not local flag
    private final BroadcastReceiver serviceStopReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateToggleButton();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvFix      = findViewById(R.id.tvFix);
        tvSats     = findViewById(R.id.tvSats);
        tvLat      = findViewById(R.id.tvLat);
        tvLon      = findViewById(R.id.tvLon);
        tvAlt      = findViewById(R.id.tvAlt);
        tvSpeed    = findViewById(R.id.tvSpeed);
        tvBtStatus = findViewById(R.id.tvBtStatus);
        tvBtDevice = findViewById(R.id.tvBtDevice);
        tvLog      = findViewById(R.id.tvLog);
        btnToggle  = findViewById(R.id.btnToggle);

        btnToggle.setOnClickListener(v -> {
            if (!deriveRunning()) startServiceAction();
            else stopServiceAction();
        });

        ContextCompat.registerReceiver(this, serviceStopReceiver,
                new IntentFilter(GpsBluetoothService.ACTION_STOPPED),
                ContextCompat.RECEIVER_NOT_EXPORTED);

        checkPermissions();
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    private void checkPermissions() {
        String[] perms = Build.VERSION.SDK_INT >= 31
                ? new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN}
                : new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN};

        List<String> needed = new ArrayList<>();
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) needed.add(p);
        }

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(
                    this, needed.toArray(new String[0]), REQ_PERMISSIONS);
        } else {
            onPermissionsGranted();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions, int[] results) {
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

    private void onPermissionsGranted() { bindToService(); }

    // -------------------------------------------------------------------------
    // Service binding
    // -------------------------------------------------------------------------

    private void bindToService() {
        Intent intent = new Intent(this, GpsBluetoothService.class);
        bindService(intent, conn, BIND_AUTO_CREATE);
    }

    private void startServiceAction() {
        startForegroundService(new Intent(this, GpsBluetoothService.class));
        if (!bound) bindToService();
        updateToggleButton();
    }

    private void stopServiceAction() {
        Intent intent = new Intent(this, GpsBluetoothService.class);
        intent.setAction(GpsBluetoothService.ACTION_STOP);
        startService(intent);
        updateToggleButton();
    }

    // -------------------------------------------------------------------------
    // UI state
    // -------------------------------------------------------------------------

    // FIX #11: single source of truth — always read from service when bound
    private boolean deriveRunning() {
        return bound && service != null && service.isRunning();
    }

    private void updateToggleButton() {
        boolean running = deriveRunning();
        btnToggle.setText(running ? "SHUTDOWN SERVER" : "INITIALIZE SERVER");
        btnToggle.setBackgroundTintList(
                ContextCompat.getColorStateList(this, running ? R.color.red : R.color.accent));
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        if (bound && service != null) {
            service.setUiCallback(uiCallback);
        }
        updateToggleButton();
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
