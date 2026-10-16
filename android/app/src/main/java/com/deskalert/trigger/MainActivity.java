package com.deskalert.trigger;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private EditText passwordInput;
    private Button sendButton, callButton;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        passwordInput = (EditText) findViewById(R.id.passwordInput);
        sendButton = (Button) findViewById(R.id.sendButton);
        callButton = (Button) findViewById(R.id.callButton);
        statusText = (TextView) findViewById(R.id.statusText);
        passwordInput.setText(Prefs.password(this));

        findViewById(R.id.logButton).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startActivity(new Intent(MainActivity.this, LogActivity.class)); }
        });
        RebindJob.schedule(this);
        findViewById(R.id.batteryButton).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try { startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))); }
                catch (Exception e) { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            }
        });
        findViewById(R.id.autostartButton).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {   // Xiaomi / HyperOS autostart page
                    Intent i = new Intent(); i.setComponent(new ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"));
                    startActivity(i);
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                }
            }
        });
        findViewById(R.id.restartButton).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                RebindJob.heal(MainActivity.this, true, "Restart button");
                toast("Asked Android to restart the listener. If nothing arrives, switch Notification access off and on.");
            }
        });
        findViewById(R.id.accessButton).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
        });
        sendButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { sendTest("MESSAGE", "Test alert", "Triggered from the app"); }
        });
        callButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { sendTest("CALL", "Incoming call", "Test call from the app"); }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        boolean on = enabled != null && enabled.contains(getPackageName());
        if (on) {                                   // opening the app always restarts the listener if it has been quiet for 10+ min
            long last = 0; try { last = Long.parseLong(Prefs.get(this, "any_ts", "0")); } catch (Exception ignored) {}
            boolean quiet = last == 0 || System.currentTimeMillis() - last > 10 * 60000L;
            if (quiet) RebindJob.heal(this, true, "App opened"); else RebindJob.rebind(this);
        }
        boolean batteryOk = false;
        try { batteryOk = ((PowerManager) getSystemService(POWER_SERVICE)).isIgnoringBatteryOptimizations(getPackageName()); } catch (Exception ignored) {}
        statusText.setText((on ? "Forwarding: ON \u2713\nAll notifications and calls are sent to your dashboard."
                              : "Forwarding: OFF\nTap below and enable Desk Alert under notification access.") + "\n\nListener connected: " + ago(Prefs.get(this, "connected_ts", "0")) + "\nLast notification from any app: " + ago(Prefs.get(this, "any_ts", "0")) + "\nWatchdog last ran: " + ago(Prefs.get(this, "watchdog_ts", "0")) + "\nBattery: " + (batteryOk ? "unrestricted \u2713" : "RESTRICTED - tap button 1 below")
                + "\nLast app seen: " + Prefs.get(this, "any", "none")
                + "\n\nWaiting to send: " + Outbox.size(this)
                + "\n\nLast notification handled:\n" + Prefs.lastSeen(this));
    }

    @Override protected void onPause() { super.onPause(); Prefs.setPassword(this, passwordInput.getText().toString().trim()); }

    private void sendTest(final String type, final String title, final String message) {
        final String password = passwordInput.getText().toString().trim();
        if (password.isEmpty()) { toast("Enter your app password"); return; }
        Prefs.setPassword(this, password);
        sendButton.setEnabled(false); callButton.setEnabled(false);
        io.execute(new Runnable() {
            public void run() {
                String r;
                try {
                    String body = new JSONObject().put("secret", password).put("appName", "Desk Alert App")
                            .put("title", title).put("message", message).put("type", type).toString();
                    Api.Result res = Api.request("POST", "/api/noti", password, body);
                    r = res.ok() ? null : res.code == 401 ? "Wrong password" : "Server error (" + res.code + ")";
                } catch (Exception e) { r = "Failed: " + e.getClass().getSimpleName() + " (check connection)"; }
                final String result = r;
                runOnUiThread(new Runnable() { public void run() {
                    sendButton.setEnabled(true); callButton.setEnabled(true);
                    toast(result == null ? "Alert sent \u2713" : result);
                }});
            }
        });
    }

    private static String ago(String ts) {
        long t; try { t = Long.parseLong(ts); } catch (Exception e) { t = 0; }
        if (t == 0) return "never";
        long m = (System.currentTimeMillis() - t) / 60000;
        return m < 1 ? "just now" : m < 120 ? m + " min ago" : (m / 60) + " h ago";
    }

    private void toast(String t) { Toast.makeText(this, t, Toast.LENGTH_SHORT).show(); }
    @Override protected void onDestroy() { super.onDestroy(); io.shutdown(); }
}
