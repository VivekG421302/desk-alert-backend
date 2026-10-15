package com.deskalert.trigger;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

/** Small persistent queue so notifications are never lost while offline; each item is sent exactly once. */
final class Outbox {
    private static final int CAP = 300;
    private static final String PREFS = "desk_alert_queue";

    private static JSONArray load(Context c) {
        try { return new JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("q", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }
    private static void save(Context c, JSONArray a) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("q", a.toString()).apply();
    }

    static synchronized void add(Context c, JSONObject item) {
        JSONArray a = load(c);
        a.put(item);
        while (a.length() > CAP) a.remove(0);
        save(c, a);
    }
    static synchronized JSONObject first(Context c) {
        JSONArray a = load(c);
        return a.length() == 0 ? null : a.optJSONObject(0);
    }
    static synchronized void dropFirst(Context c) {
        JSONArray a = load(c);
        if (a.length() > 0) { a.remove(0); save(c, a); }
    }
    static synchronized int size(Context c) { return load(c).length(); }
}
