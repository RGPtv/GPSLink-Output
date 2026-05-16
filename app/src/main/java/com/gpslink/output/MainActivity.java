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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import com.google.android.material.bottomnavigation.BottomNavigationView;



public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMISSIONS = 1;

    private TextView tvFix, tvSats, tvLat, tvLon, tvAlt, tvSpeed,
            tvBtStatus, tvBtDevice, tvLog, tvSatelliteDetails;
    private android.widget.TableLayout tableSatelliteList;
    private Button btnToggle;
    private View homeView, satelliteView, terminalView;
    private BottomNavigationView bottomNavigation;

    private GpsBluetoothService service;
    private boolean bound = false;

    private static final int MAX_LOGS = 20;
    private static final int MAX_SAT_ROWS = 50;
    private static final int TABLE_HEADER_ROW_COUNT = 1;
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

    private final GpsBluetoothService.UiCallback uiCallback = new GpsBluetoothService.UiCallback() {

        @Override
        public void onGpsUpdate(boolean hasFix, int satsUsed, int satsInView,
                double lat, double lon, double alt, float speed,
                java.util.List<GpsBluetoothService.SatInfo> satDetails) {
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
                } else {
                    tvLat.setText("—");
                    tvLon.setText("—");
                    tvAlt.setText("—");
                    tvSpeed.setText("—");
                }
                if (tvSatelliteDetails != null) {
                    tvSatelliteDetails.setText("Sats In View: " + satsInView + "\nSats Used: " + satsUsed + "\nFix: "
                            + (hasFix ? "Acquired" : "Pending"));
                }
                if (tableSatelliteList != null) {
                    int currentRows = tableSatelliteList.getChildCount() - TABLE_HEADER_ROW_COUNT;
                    int requiredRows = satDetails.size();

                    // Add any missing rows needed
                    while (currentRows < requiredRows && currentRows < MAX_SAT_ROWS) {
                        android.widget.TableRow row = new android.widget.TableRow(MainActivity.this);
                        row.setPadding(0, 8, 0, 8);
                        for (int i = 0; i < 6; i++) {
                            row.addView(createTableCell(""));
                        }
                        tableSatelliteList.addView(row);
                        currentRows++;
                    }

                    // Update rows and hide excess ones
                    for (int i = 0; i < currentRows; i++) {
                        android.widget.TableRow row = (android.widget.TableRow) tableSatelliteList.getChildAt(i + TABLE_HEADER_ROW_COUNT);
                        if (i < requiredRows) {
                            row.setVisibility(android.view.View.VISIBLE);
                            GpsBluetoothService.SatInfo sat = satDetails.get(i);
                            ((TextView) row.getChildAt(0)).setText(sat.prn);
                            ((TextView) row.getChildAt(1)).setText(sat.gnss);
                            ((TextView) row.getChildAt(2)).setText(sat.snr);
                            ((TextView) row.getChildAt(3)).setText(sat.elev);
                            ((TextView) row.getChildAt(4)).setText(sat.azim);
                            ((TextView) row.getChildAt(5)).setText(sat.isUsed ? "✓" : "");
                        } else {
                            row.setVisibility(android.view.View.GONE);
                        }
                    }
                }
            });
        }

        private TextView createTableCell(String text) {
            TextView tv = new TextView(MainActivity.this);
            tv.setText(text);
            tv.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.text_primary));
            tv.setTextSize(11f);
            tv.setPadding(4, 4, 4, 4);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            return tv;
        }

        @Override
        public void onBluetoothStatus(String status, String deviceName, boolean connected) {
            runOnUiThread(() -> {
                tvBtStatus.setText(status.toUpperCase(java.util.Locale.US));
                tvBtStatus.setTextColor(ContextCompat.getColor(
                        MainActivity.this, connected ? R.color.green : R.color.text_secondary));
                tvBtDevice.setText(deviceName != null ? deviceName : "None");
            });
        }

        @Override
        public void onNmeaLog(String message) {
            runOnUiThread(() -> {
                String trimmed = message.trim();
                if (logLineCount >= MAX_LOGS) {
                    // Remove the first line
                    int nl = logBuilder.indexOf("\n");
                    if (nl >= 0)
                        logBuilder.delete(0, nl + 1);
                    else
                        logBuilder.setLength(0);
                    logLineCount--;
                }
                if (logBuilder.length() > 0)
                    logBuilder.append('\n');
                logBuilder.append(trimmed);
                logLineCount++;
                tvLog.setText(logBuilder.toString());
            });
        }

        @Override
        public void onDroppedMessages(int count) {
            // Optional: log or display dropped message count
        }

        @Override
        public void onServiceStateChanged(boolean isRunning) {
            runOnUiThread(() -> updateToggleButton());
        }
    };

    // React to service stop via broadcast
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

        tvFix = findViewById(R.id.tvFix);
        tvSats = findViewById(R.id.tvSats);
        tvLat = findViewById(R.id.tvLat);
        tvLon = findViewById(R.id.tvLon);
        tvAlt = findViewById(R.id.tvAlt);
        tvSpeed = findViewById(R.id.tvSpeed);
        tvBtStatus = findViewById(R.id.tvBtStatus);
        tvBtDevice = findViewById(R.id.tvBtDevice);
        tvLog = findViewById(R.id.tvLog);
        tvSatelliteDetails = findViewById(R.id.tvSatelliteDetails);
        tableSatelliteList = findViewById(R.id.tableSatelliteList);
        btnToggle = findViewById(R.id.btnToggle);

        homeView = findViewById(R.id.homeView);
        satelliteView = findViewById(R.id.satelliteView);
        terminalView = findViewById(R.id.terminalView);
        bottomNavigation = findViewById(R.id.bottomNavigation);

        bottomNavigation.setOnItemSelectedListener(item -> {
            homeView.setVisibility(View.GONE);
            satelliteView.setVisibility(View.GONE);
            terminalView.setVisibility(View.GONE);
            int id = item.getItemId();
            if (id == R.id.nav_home) {
                homeView.setVisibility(View.VISIBLE);
                return true;
            } else if (id == R.id.nav_satellite) {
                satelliteView.setVisibility(View.VISIBLE);
                return true;
            } else if (id == R.id.nav_terminal) {
                terminalView.setVisibility(View.VISIBLE);
                return true;
            }
            return false;
        });

        btnToggle.setOnClickListener(v -> {
            if (!deriveRunning())
                startServiceAction();
            else
                stopServiceAction();
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
                ? new String[] {
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN }
                : new String[] {
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.BLUETOOTH,
                        Manifest.permission.BLUETOOTH_ADMIN };

        List<String> needed = new ArrayList<>();
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED)
                needed.add(p);
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

    private void onPermissionsGranted() {
        bindToService();
    }

    // -------------------------------------------------------------------------
    // Service binding
    // -------------------------------------------------------------------------

    private void bindToService() {
        Intent intent = new Intent(this, GpsBluetoothService.class);
        bindService(intent, conn, BIND_AUTO_CREATE);
    }

    private void startServiceAction() {
        startForegroundService(new Intent(this, GpsBluetoothService.class));
        if (!bound)
            bindToService();
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

    // Single source of truth — always read from service when bound
    private boolean deriveRunning() {
        return bound && service != null && service.isRunning();
    }

    private void updateToggleButton() {
        boolean running = deriveRunning();
        btnToggle.setText(running ? "SHUTDOWN SERVER" : "INITIALIZE SERVER");
        btnToggle.setBackgroundTintList(
                ContextCompat.getColorStateList(this, running ? R.color.red : R.color.green));
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        if (bound && service != null) {
            service.setUiCallback(uiCallback);
            logBuilder.setLength(0);
            logLineCount = 0;
            // Replay buffered NMEA lines from background
            for (String line : service.getRecentNmea()) {
                uiCallback.onNmeaLog(line);
            }
        }
        updateToggleButton();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (bound && service != null)
            service.setUiCallback(null);
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
