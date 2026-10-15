package com.deskalert.trigger;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Forwards WhatsApp messages/calls to the backend and sends dashboard replies via the notification's inline reply. */
public class NotifService extends NotificationListenerService {
    private static final long HOLD_MS = 30 * 60 * 1000L;
    private static final long POLL_MS = 4000L;

    private static final class Held {
        final Notification.Action action; final String sbnKey; final long time;
        Held(Notification.Action a, String k) { action = a; sbnKey = k; time = System.currentTimeMillis(); }
    }

    private final Map<String, Held> held = new ConcurrentHashMap<String, Held>();      // reply token -> inline reply action
    private final Map<String, String> lastSig = new ConcurrentHashMap<String, String>(); // sbn key -> last title|text
    private HandlerThread thread;
    private Handler handler;
    private boolean polling;

    @Override public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("deskalert-net");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    @Override public void onDestroy() { super.onDestroy(); thread.quit(); }

    private static String str(CharSequence cs) { return cs == null ? "" : cs.toString().trim(); }

    private static String firstNonEmpty(Bundle e, String... keys) {
        for (String k : keys) { String v = str(e.getCharSequence(k)); if (!v.isEmpty()) return v; }
        return "";
    }

    @Override public void onListenerConnected() { super.onListenerConnected(); Prefs.put(this, "connected", Prefs.now()); }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            Prefs.put(this, "any", sbn.getPackageName() + " at " + Prefs.now());
            handle(sbn);
        } catch (Throwable t) {
            Prefs.setLastSeen(this, "ERROR: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    private void handle(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (!"com.whatsapp".equals(pkg) && !"com.whatsapp.w4b".equals(pkg)) return;
        final String password = Prefs.password(this);
        if (password.isEmpty()) return;

        Notification n = sbn.getNotification();
        Bundle e = n.extras;
        final String title = firstNonEmpty(e, Notification.EXTRA_TITLE, Notification.EXTRA_TITLE_BIG);
        final String text = firstNonEmpty(e, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT);
        String all = (title + " " + text).toLowerCase();
        int actionCount = n.actions == null ? 0 : n.actions.length;
        boolean fullScreen = n.fullScreenIntent != null;
        boolean missedOrOngoing = all.contains("missed") || all.contains("ongoing") || all.contains("ended") || all.contains("declined");
        // Incoming call = ringing: full-screen intent, or "incoming ... call" text, or call category with Answer/Decline actions
        final boolean call = !missedOrOngoing
                && (fullScreen || (all.contains("incoming") && all.contains("call"))
                    || (Notification.CATEGORY_CALL.equals(n.category) && actionCount >= 2));
        String info = "title=" + title + " | text=" + text + " | cat=" + n.category + " | flags=" + n.flags
                + " | fullScreen=" + fullScreen + " | actions=" + actionCount;

        if (!call) {
            String skip = null;
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) skip = "group summary";            // duplicates every message
            else if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) skip = "ongoing";            // "WhatsApp Web is active", etc.
            else if (text.isEmpty() || title.equalsIgnoreCase("WhatsApp") || title.equalsIgnoreCase("WhatsApp Business")) skip = "generic/empty";
            else if (text.toLowerCase().matches("^\\d+ new messages?.*")) skip = "count summary";
            if (skip != null) { Prefs.setLastSeen(this, "SKIPPED (" + skip + ")\n" + info); return; }
        }

        final String key = sbn.getKey();
        String sig = title + "|" + text;
        if (sig.equals(lastSig.get(key))) return;                                          // re-post of the same notification
        lastSig.put(key, sig);
        Prefs.setLastSeen(this, (call ? "SENT as CALL" : "SENT as MESSAGE") + "\n" + info);

        String token = "";
        if (!call && n.actions != null) {
            for (Notification.Action a : n.actions) {
                RemoteInput[] ris = a.getRemoteInputs();
                if (ris != null && ris.length > 0) { token = UUID.randomUUID().toString(); held.put(token, new Held(a, key)); break; }
            }
        }
        final String replyKey = token;
        final boolean isCall = call;
        final String appName = "com.whatsapp.w4b".equals(pkg) ? "WhatsApp Business" : "WhatsApp";

        handler.post(new Runnable() { public void run() {
            try {
                JSONObject body = new JSONObject().put("secret", password).put("appName", appName)
                        .put("title", title.isEmpty() ? (isCall ? "WhatsApp call" : "Unknown") : title)
                        .put("message", text.isEmpty() ? (isCall ? "Incoming call" : "(no content)") : text)
                        .put("type", isCall ? "CALL" : "MESSAGE");
                if (!replyKey.isEmpty()) body.put("replyKey", replyKey);
                Api.Result r = Api.request("POST", "/api/noti", password, body.toString());
                if (!r.ok()) held.remove(replyKey);
                else if (!replyKey.isEmpty()) ensurePolling();
            } catch (Exception ex) { held.remove(replyKey); }
        }});
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        String key = sbn.getKey();
        lastSig.remove(key);
        for (Iterator<Map.Entry<String, Held>> it = held.entrySet().iterator(); it.hasNext();) {
            if (it.next().getValue().sbnKey.equals(key)) it.remove();
        }
    }

    // ---- reply polling: runs only while at least one reply-able notification is held ----
    private void ensurePolling() {
        if (!polling) { polling = true; handler.postDelayed(pollTask, POLL_MS); }
    }

    private final Runnable pollTask = new Runnable() { public void run() {
        try { pollReplies(); } catch (Exception ignored) {}
        if (!held.isEmpty()) handler.postDelayed(this, POLL_MS); else polling = false;
    }};

    private void pollReplies() throws Exception {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<String, Held>> it = held.entrySet().iterator(); it.hasNext();) {
            if (now - it.next().getValue().time > HOLD_MS) it.remove();
        }
        String password = Prefs.password(this);
        if (password.isEmpty()) return;
        Api.Result r = Api.request("GET", "/api/replies", password, null);
        if (!r.ok()) return;
        JSONArray items = new JSONObject(r.body).getJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.getJSONObject(i);
            Held h = held.get(it.getString("reply_key"));
            boolean ok = false; String err = "Notification no longer on phone";
            if (h != null) {
                try { sendReply(h.action, it.getString("text")); ok = true; err = null; }
                catch (Exception ex) { err = "Could not send: " + ex.getClass().getSimpleName(); }
            }
            JSONObject ack = new JSONObject().put("id", it.getString("id")).put("ok", ok);
            if (err != null) ack.put("error", err);
            Api.request("POST", "/api/replies", password, ack.toString());
        }
    }

    private void sendReply(Notification.Action action, String text) throws PendingIntent.CanceledException {
        RemoteInput[] ris = action.getRemoteInputs();
        Intent intent = new Intent();
        Bundle results = new Bundle();
        results.putCharSequence(ris[0].getResultKey(), text);
        RemoteInput.addResultsToIntent(ris, intent, results);
        action.actionIntent.send(this, 0, intent);
    }
}
