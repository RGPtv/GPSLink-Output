package com.gpslink.output;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            String addr = Prefs.getLastDevice(context);
            if (addr != null) {
                Intent svc = new Intent(context, GpsBluetoothService.class);
                svc.putExtra(GpsBluetoothService.EXTRA_DEVICE_ADDRESS, addr);
                context.startForegroundService(svc);
            }
        }
    }
}
