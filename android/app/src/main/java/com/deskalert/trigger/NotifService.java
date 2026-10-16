package com.deskalert.trigger;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import org.json.JSONArray;
import org.json.JSONObject;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Forwards every notification (any app) to the backend exactly once.
 *  - chat notifications (MessagingStyle): each new message is sent individually
 *  - inbox-style notifications: each new line is sent individually
 *  - everything else: sent when its content changes
 *  - incoming calls (WhatsApp + normal phone calls): sent as CALL, removed again when the ringing ends
 * Items go through a persistent queue with retry, so nothing is lost while offline.
 */
public class NotifService extends NotificationListenerService {
    private static final String WA = "com.whatsapp";
    private static final String WA_BIZ = "com.whatsapp.w4b";
    private static final long HOLD_MS = 30 * 60 * 1000L;
    private static final long POLL_MS = 4000L;
    private static final long ONGOING_THROTTLE_MS = 30000L;
    private static final int MAX_MESSAGES_PER_POST = 10;

    private static final class Held {
        final Notification.Action action; final String sbnKey; final long time;
        Held(Notification.Action a, String k) { action = a; sbnKey = k; time = System.currentTimeMillis(); }
    }

    private final Map<String, Held> held = new ConcurrentHashMap<String, Held>();              // reply token -> inline reply action
    private final Map<String, String> lastSig = new ConcurrentHashMap<String, String>();         // sbn key -> last title|text (plain notifications)
    private final Map<String, Long> lastMsgTime = new ConcurrentHashMap<String, Long>();          // sbn key -> newest chat message already sent
    private final Map<String, Set<String>> seenLines = new ConcurrentHashMap<String, Set<String>>(); // sbn key -> inbox lines already sent
    private final Map<String, Long> lastSent = new ConcurrentHashMap<String, Long>();             // sbn key -> time of last send (throttle ongoing)
    private final Set<String> callKeys = new HashSet<String>();                                   // ringing calls queued/sent (handler thread only)
    private final Map<String, String> callIds = new ConcurrentHashMap<String, String>();          // sbn key -> dashboard id of a ringing call
    private final Set<String> cancelledCalls = new HashSet<String>();                             // ring ended before the card was sent
    private static final long WARM_MS = 4 * 60 * 1000L;
    private HandlerThread thread, pollThread;
    private Handler handler;          // sender thread: outbox flush, call removal, keep-warm (never blocked by reply polling)
    private Handler pollHandler;      // reply polling thread
    private boolean polling;          // pollHandler thread only
    private long backoff = 1000;      // quick retries: 1s, 2s, 4s, then every 5s
    private ConnectivityManager.NetworkCallback netCb;

    @Override public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("deskalert-send");
        thread.start();
        handler = new Handler(thread.getLooper());
        pollThread = new HandlerThread("deskalert-reply");
        pollThread.start();
        pollHandler = new Handler(pollThread.getLooper());
        handler.postDelayed(warmTask, 2000);
        try {   // the moment the network is back, flush immediately
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            netCb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { backoff = 1000; kickFlush(); }
            };
            cm.registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), netCb);
        } catch (Exception ignored) {}
    }

    @Override public void onDestroy() {
        super.onDestroy();
        try { if (netCb != null) ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(netCb); } catch (Exception ignored) {}
        thread.quit();
        pollThread.quit();
    }

    /** Keeps the HTTPS connection and the serverless function warm so the next notification is sent in ~1 s. */
    private final Runnable warmTask = new Runnable() { public void run() {
        try { String pw = Prefs.password(NotifService.this); if (!pw.isEmpty()) Api.request("GET", "/api/health", pw, null); } catch (Exception ignored) {}
        handler.postDelayed(this, WARM_MS);
    }};

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        Prefs.put(this, "connected", Prefs.now());
        Prefs.put(this, "connected_ts", String.valueOf(System.currentTimeMillis()));
        Journal.add(this, "Desk Alert", "Notification listener", "CONNECTED");
        kickFlush();                                           // send anything left in the queue
    }

    // API 24+: called by Android when the listener is unbound (no @Override: the build SDK here is older)
    public void onListenerDisconnected() {
        Journal.add(this, "Desk Alert", "Notification listener", "DISCONNECTED - asking Android to reconnect");
        try { NotificationListenerService.class.getMethod("requestRebind", ComponentName.class).invoke(null, new ComponentName(this, NotifService.class)); }
        catch (Exception ignored) {}
    }

    private static String str(CharSequence cs) { return cs == null ? "" : cs.toString().trim(); }

    private static String firstNonEmpty(Bundle e, String... keys) {
        for (String k : keys) { String v = str(e.getCharSequence(k)); if (!v.isEmpty()) return v; }
        return "";
    }

    private static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception ex) { return Integer.toHexString(s.hashCode()); }
    }

    private String labelFor(String pkg) {
        if (WA.equals(pkg)) return "WhatsApp";
        if (WA_BIZ.equals(pkg)) return "WhatsApp Business";
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception ex) {
            String last = pkg.substring(pkg.lastIndexOf('.') + 1);
            return last.isEmpty() ? pkg : Character.toUpperCase(last.charAt(0)) + last.substring(1);
        }
    }

    private JSONObject item(String appName, String title, String message, String type, long postedAt, String idSeed) throws Exception {
        return new JSONObject().put("appName", appName)
                .put("title", title.isEmpty() ? appName : title)
                .put("message", message.isEmpty() ? (type.equals("CALL") ? "Incoming call" : "(no content)") : message)
                .put("type", type).put("postedAt", postedAt).put("clientId", sha(idSeed))
                .put("_q", System.currentTimeMillis());
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            Prefs.put(this, "any", sbn.getPackageName() + " at " + Prefs.now());
            Prefs.put(this, "any_ts", String.valueOf(System.currentTimeMillis()));
            handle(sbn);
        } catch (Throwable t) {
            Prefs.setLastSeen(this, "ERROR: " + t.getClass().getSimpleName() + " " + t.getMessage());
            Journal.add(this, sbn.getPackageName(), "", "ERROR: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    private void handle(StatusBarNotification sbn) throws Exception {
        final String pkg = sbn.getPackageName();
        if (getPackageName().equals(pkg)) return;                                  // never forward our own notifications
        final String password = Prefs.password(this);
        if (password.isEmpty()) { Journal.add(this, pkg, "", "NOT SENT: enter your app password in Desk Alert"); return; }

        Notification n = sbn.getNotification();
        Bundle e = n.extras;
        final String title = firstNonEmpty(e, Notification.EXTRA_TITLE, Notification.EXTRA_TITLE_BIG);
        String text = firstNonEmpty(e, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT);
        String big = str(e.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (big.length() > text.length()) text = big;                                // full text instead of the truncated line
        final String fullText = text;
        String all = (title + " " + text).toLowerCase();
        int actionCount = n.actions == null ? 0 : n.actions.length;
        boolean fullScreen = n.fullScreenIntent != null;
        boolean wa = WA.equals(pkg) || WA_BIZ.equals(pkg);
        boolean missed = all.contains("missed");
        boolean missedOrOngoing = missed || all.contains("ongoing") || all.contains("ended") || all.contains("declined");
        boolean incomingText = all.contains("incoming") && all.contains("call");
        boolean callCategory = Notification.CATEGORY_CALL.equals(n.category);
        boolean dialerLike = pkg.contains("incallui") || pkg.contains("dialer") || pkg.contains("telecom") || pkg.equals("com.android.phone");

        final boolean call;
        if (wa) {
            call = !missedOrOngoing && (fullScreen || incomingText || (callCategory && actionCount >= 2));   // rule that already works
        } else {
            call = !missedOrOngoing && (callCategory || dialerLike) && (fullScreen || incomingText);
        }
        final String key = sbn.getKey();
        final String appName = labelFor(pkg);
        String info = "pkg=" + pkg + " | title=" + title + " | text=" + text + " | cat=" + n.category + " | flags=" + n.flags
                + " | fullScreen=" + fullScreen + " | actions=" + actionCount;

        // a ringing call that turned into something else (answered / ongoing) -> remove its dashboard card
        if (!call) { final String k = key; handler.post(new Runnable() { public void run() { if (callKeys.contains(k)) endCall(k, password); } }); }

        // ---------- incoming call ----------
        if (call) {
            String sig = title + "|" + text;
            if (sig.equals(lastSig.get(key))) return;
            lastSig.put(key, sig);
            Journal.add(this, appName, title + ": " + text, "QUEUED (incoming call)");
            final JSONObject it = item(appName, title.isEmpty() ? (wa ? "WhatsApp call" : "Incoming call") : title, text, "CALL",
                    System.currentTimeMillis(), pkg + "|" + key + "|call|" + sbn.getPostTime());
            it.put("_key", key);
            Prefs.setLastSeen(this, "SENT as CALL\n" + info);
            handler.post(new Runnable() { public void run() { callKeys.add(key); } });
            Outbox.add(this, it);
            kickFlush();
            return;
        }

        // ---------- everything else ----------
        int f = n.flags;
        boolean ongoing = (f & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE)) != 0;
        String skip = null;
        if ((f & Notification.FLAG_GROUP_SUMMARY) != 0) skip = "group summary";                              // its children are sent themselves
        else if (Notification.CATEGORY_PROGRESS.equals(n.category) || e.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0
                || e.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)) skip = "progress bar";
        else if (callCategory && !missed) skip = "call in progress";
        else if (wa && (title.equalsIgnoreCase("WhatsApp") || title.equalsIgnoreCase("WhatsApp Business"))) skip = "generic WhatsApp";
        else if (wa && all.matches("^.*\\b\\d+ new messages?.*$")) skip = "count summary";
        if (skip != null) {
            Prefs.setLastSeen(this, "SKIPPED (" + skip + ")\n" + info);
            Journal.add(this, appName, title + ": " + fullText, "SKIPPED: " + skip);
            return;
        }

        List<JSONObject> items = new ArrayList<JSONObject>();
        Parcelable[] msgs = e.getParcelableArray("android.messages");
        boolean timed = msgs != null && msgs.length > 0;
        if (timed) {
            for (Parcelable p : msgs) { if (!(p instanceof Bundle) || ((Bundle) p).getLong("time") == 0) { timed = false; break; } }
        }
        if (timed) {
            // chat: send each message we have not sent yet
            String conv = str(e.getCharSequence("android.conversationTitle"));
            String chatTitle = !conv.isEmpty() ? conv : title.replaceAll("\\s*\\(\\d+ messages?\\)$", "");
            Long seen = lastMsgTime.get(key);
            long last = seen == null ? 0 : seen, maxT = last;
            for (int i = Math.max(0, msgs.length - MAX_MESSAGES_PER_POST); i < msgs.length; i++) {
                Bundle m = (Bundle) msgs[i];
                long t = m.getLong("time");
                String mt = str(m.getCharSequence("text"));
                if (mt.isEmpty() || t <= last) continue;
                String sender = str(m.getCharSequence("sender"));
                String body = (!conv.isEmpty() && !sender.isEmpty()) ? sender + ": " + mt : mt;
                items.add(item(appName, chatTitle, body, "MESSAGE", t, pkg + "|" + t + "|" + sender + "|" + mt));
                if (t > maxT) maxT = t;
            }
            if (maxT > last) lastMsgTime.put(key, maxT);
        } else {
            CharSequence[] lines = e.getCharSequenceArray("android.textLines");
            if (lines != null && lines.length > 0) {
                Set<String> seen = seenLines.get(key);
                if (seen == null) { seen = new HashSet<String>(); seenLines.put(key, seen); }
                for (CharSequence cs : lines) {
                    String line = str(cs);
                    if (!line.isEmpty() && seen.add(line))
                        items.add(item(appName, title, line, "MESSAGE", System.currentTimeMillis(), pkg + "|" + key + "|" + line));
                }
                if (seen.size() > 60) seen.clear();
            } else if (!(title.isEmpty() && fullText.isEmpty())) {
                String sig = title + "|" + fullText;
                Long ls = lastSent.get(key);
                boolean throttled = ongoing && ls != null && System.currentTimeMillis() - ls < ONGOING_THROTTLE_MS;
                if (!sig.equals(lastSig.get(key)) && !throttled) {
                    lastSig.put(key, sig);
                    items.add(item(appName, title, fullText, "MESSAGE", sbn.getPostTime(), pkg + "|" + key + "|" + sig + "|" + sbn.getPostTime()));
                }
            }
        }
        if (items.isEmpty()) {
            if (!ongoing) Journal.add(this, appName, title + ": " + fullText, "IGNORED: already sent");
            return;
        }
        lastSent.put(key, System.currentTimeMillis());
        if (lastMsgTime.size() > 500) lastMsgTime.clear();
        if (lastSent.size() > 500) lastSent.clear();

        // inline reply: attach a token to the newest item
        if (n.actions != null) {
            for (Notification.Action a : n.actions) {
                RemoteInput[] ris = a.getRemoteInputs();
                if (ris != null && ris.length > 0) {
                    String token = UUID.randomUUID().toString();
                    held.put(token, new Held(a, key));
                    items.get(items.size() - 1).put("replyKey", token);
                    break;
                }
            }
        }
        Prefs.setLastSeen(this, "SENT " + items.size() + " as MESSAGE\n" + info);
        for (JSONObject it : items) {
            Outbox.add(this, it);
            Journal.add(this, appName, it.optString("title") + ": " + it.optString("message"), "QUEUED");
        }
        kickFlush();
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        final String key = sbn.getKey();
        lastSig.remove(key);
        seenLines.remove(key);                                  // lastMsgTime is kept on purpose: never resend old chat messages
        for (Iterator<Map.Entry<String, Held>> it = held.entrySet().iterator(); it.hasNext();) {
            if (it.next().getValue().sbnKey.equals(key)) it.remove();
        }
        // ringing ended (missed / declined / answered): take the call card off the dashboard
        final String password = Prefs.password(this);
        handler.post(new Runnable() { public void run() { if (callKeys.contains(key)) endCall(key, password); } });
    }

    /** Handler thread. */
    private void endCall(String key, String password) {
        callKeys.remove(key);
        Journal.add(this, "Call", "Ringing ended", "CARD REMOVED");
        String id = callIds.remove(key);
        if (id == null) { cancelledCalls.add(key); return; }                   // not sent yet: flush will drop it
        if (password.isEmpty()) return;
        try { Api.request("DELETE", "/api/notification?id=" + id, password, null); } catch (Exception ignored) {}
    }

    // ---------- outbox flushing (handler thread) ----------
    private void kickFlush() { handler.removeCallbacks(flushTask); handler.post(flushTask); }

    private final Runnable flushTask = new Runnable() { public void run() {
        boolean retry;
        try { retry = flushOnce(); } catch (Throwable t) { retry = true; }
        if (retry) { handler.postDelayed(this, backoff); backoff = Math.min(backoff * 2, 5000L); }
        else backoff = 1000;
    }};

    /** @return true if sending should be retried later (network down / server error) */
    private boolean flushOnce() throws Exception {
        String password = Prefs.password(this);
        if (password.isEmpty()) return false;
        while (true) {
            JSONObject it = Outbox.first(this);
            if (it == null) return false;
            String type = it.optString("type");
            String key = it.optString("_key", "");
            if ("CALL".equals(type)) {
                long age = System.currentTimeMillis() - it.optLong("postedAt", System.currentTimeMillis());
                if (age > 90000L || cancelledCalls.remove(key)) {                                                    // stale ring
                    Outbox.dropFirst(this); callKeys.remove(key);
                    Journal.add(this, it.optString("appName"), it.optString("title"), "SKIPPED: call already ended");
                    continue;
                }
            }
            JSONObject body = new JSONObject(it.toString()).put("secret", password);
            Api.Result r;
            String sum = it.optString("title") + ": " + it.optString("message");
            long t0 = System.currentTimeMillis();
            try { r = Api.request("POST", "/api/noti", password, body.toString()); }
            catch (Exception ex) {
                Journal.add(this, it.optString("appName"), sum, "OFFLINE: " + ex.getClass().getSimpleName() + " - retrying in " + (backoff / 1000) + "s");
                return true;
            }
            if (r.ok()) {
                Outbox.dropFirst(this);
                long now = System.currentTimeMillis();
                Journal.add(this, it.optString("appName"), sum, "SENT in " + (now - it.optLong("_q", now)) + " ms (network " + (now - t0) + " ms, HTTP " + r.code
                        + (r.body.contains("\"duplicate\"") ? ", duplicate ignored" : "") + ")");
                if ("CALL".equals(type)) {
                    String id = new JSONObject(r.body).optString("id", "");
                    if (!id.isEmpty()) callIds.put(key, id);
                }
                if (!it.optString("replyKey", "").isEmpty()) ensurePolling();
            } else if (r.code >= 500 || r.code == 429) {
                Journal.add(this, it.optString("appName"), sum, "FAILED: server error HTTP " + r.code + " - retrying in " + (backoff / 1000) + "s");
                return true;
            } else {
                Journal.add(this, it.optString("appName"), sum, "REJECTED: HTTP " + r.code + (r.code == 401 ? " (wrong password)" : ""));
                Outbox.dropFirst(this);                                         // bad request / wrong password: do not loop forever
                held.remove(it.optString("replyKey", ""));
            }
        }
    }

    // ---------- reply polling: runs only while at least one reply-able notification is held ----------
    private void ensurePolling() {
        pollHandler.post(new Runnable() { public void run() {
            if (!polling) { polling = true; pollHandler.postDelayed(pollTask, POLL_MS); }
        }});
    }

    private final Runnable pollTask = new Runnable() { public void run() {
        try { pollReplies(); } catch (Exception ignored) {}
        if (!held.isEmpty()) pollHandler.postDelayed(this, POLL_MS); else polling = false;
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
