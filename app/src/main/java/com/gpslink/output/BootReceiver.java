package com.gpslink.output;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        // FIX #4: Check location permission before starting foreground service.
        // On API 34+, missing permission causes ForegroundServiceStartNotAllowedException.
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        // FIX #5: getLastDevice() now returns a real address (saved by GpsBluetoothService
        // when a client connects). Previously saveLastDevice() was never called, so this
        // always returned null and boot auto-start was permanently broken.
        String addr = Prefs.getLastDevice(context);
        if (addr != null) {
            Intent svc = new Intent(context, GpsBluetoothService.class);
            svc.putExtra(GpsBluetoothService.EXTRA_DEVICE_ADDRESS, addr);
            context.startForegroundService(svc);
        }
    }
}
