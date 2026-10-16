package com.stu.chargewatch;

import android.content.Context;
import android.content.SharedPreferences;

final class Settings {
    static SharedPreferences prefs(Context context) { return context.getSharedPreferences("charge_settings", Context.MODE_PRIVATE); }
    static String name(Context context, String id) { return Station.name(id); }
    static boolean auto(Context context) { return prefs(context).getBoolean("auto", true); }
    static boolean watch(Context context) { return prefs(context).getBoolean("watch", false); }
    static void setAuto(Context context, boolean value) { prefs(context).edit().putBoolean("auto", value).apply(); }
    static void setWatch(Context context, boolean value) { prefs(context).edit().putBoolean("watch", value).apply(); }
    static void setName(Context context, String id, String value) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (value.trim().isEmpty()) editor.remove("name_" + id); else editor.putString("name_" + id, value.trim());
        editor.apply();
    }
}
