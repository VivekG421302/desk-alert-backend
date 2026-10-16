package com.deskalert.trigger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After reboot or app update: restart the watchdog and reconnect the notification listener. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        RebindJob.schedule(c);
        RebindJob.rebind(c);
        Journal.add(c, "Desk Alert", "Phone restarted / app updated", "WATCHDOG: reconnecting listener");
    }
}
