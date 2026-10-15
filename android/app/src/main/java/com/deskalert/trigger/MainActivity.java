package com.deskalert.trigger;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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
        if (on) { try { NotificationListenerService.class.getMethod("requestRebind", ComponentName.class).invoke(null, new ComponentName(this, NotifService.class)); } catch (Exception ignored) {} }  // revive listener after app updates
        statusText.setText((on ? "WhatsApp forwarding: ON \u2713\nMessages and calls are sent to your dashboard."
                              : "WhatsApp forwarding: OFF\nTap below and enable Desk Alert under notification access.") + "\n\nListener connected: " + Prefs.get(this, "connected", "never")
                + "\nLast notification from any app: " + Prefs.get(this, "any", "none")
                + "\n\nLast WhatsApp notification seen:\n" + Prefs.lastSeen(this));
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

    private void toast(String t) { Toast.makeText(this, t, Toast.LENGTH_SHORT).show(); }
    @Override protected void onDestroy() { super.onDestroy(); io.shutdown(); }
}
