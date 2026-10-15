package com.deskalert.trigger;

import android.content.Context;

final class Prefs {
    static String password(Context c) { return c.getSharedPreferences("desk_alert", Context.MODE_PRIVATE).getString("password", ""); }
    static void setPassword(Context c, String p) { c.getSharedPreferences("desk_alert", Context.MODE_PRIVATE).edit().putString("password", p).apply(); }
    static void setLastSeen(Context c, String v) { c.getSharedPreferences("desk_alert", Context.MODE_PRIVATE).edit().putString("last_seen", v).apply(); }
    static String lastSeen(Context c) { return c.getSharedPreferences("desk_alert", Context.MODE_PRIVATE).getString("last_seen", "none yet"); }
    static void put(Context c, String k, String v) { c.getSharedPreferences("desk_alert", Context.MODE_PRIVATE).edit().putString(k, v).apply(); }
    static String get(Context c, String k, String def) { return c.getSharedPreferences("desk_alert", Context.MODE_PRIVATE).getString(k, def); }
    static String now() { return new java.text.SimpleDateFormat("d MMM HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date()); }
}
