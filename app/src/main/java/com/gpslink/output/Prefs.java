package com.gpslink.output;

import android.content.Context;
import android.content.SharedPreferences;

// No changes required. saveLastDevice() is now called by GpsBluetoothService
// when a client connects, making boot auto-start functional (FIX #5).
final class Prefs {
    private static final String NAME       = "gpslink";
    private static final String KEY_DEVICE = "last_device";

    static void saveLastDevice(Context ctx, String address) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_DEVICE, address).apply();
    }

    static String getLastDevice(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getString(KEY_DEVICE, null);
    }
}
