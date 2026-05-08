package com.gpslink.output;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    private static final String NAME = "gpslink";
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
