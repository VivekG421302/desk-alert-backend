package com.deskalert.trigger;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/** Persistent on-device activity log (newest first) so you can see what the app received and what happened to it. */
final class Journal {
    private static final int CAP = 200;
    private static final String PREFS = "desk_alert_log";

    private static String clean(String s, int max) {
        if (s == null) return "";
        s = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > max ? s.substring(0, max) + "\u2026" : s;
    }

    static synchronized void add(Context c, String app, String text, String status) {
        String line = System.currentTimeMillis() + "\t" + clean(app, 40) + "\t" + clean(text, 140) + "\t" + clean(status, 120);
        String cur = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("log", "");
        String next = cur.isEmpty() ? line : line + "\n" + cur;
        int idx = -1;
        for (int i = 0; i < CAP; i++) { idx = next.indexOf('\n', idx + 1); if (idx < 0) break; }
        if (idx >= 0) next = next.substring(0, idx);
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("log", next).apply();
    }

    /** Each entry: {time, app, text, status} */
    static synchronized List<String[]> all(Context c) {
        List<String[]> out = new ArrayList<String[]>();
        String cur = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("log", "");
        if (cur.isEmpty()) return out;
        for (String line : cur.split("\n")) {
            String[] p = line.split("\t", -1);
            if (p.length == 4) out.add(p);
        }
        return out;
    }

    static synchronized void clear(Context c) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("log").apply(); }
}
