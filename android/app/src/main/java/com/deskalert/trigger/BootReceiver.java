package com.deskalert.trigger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After reboot or app update: restart the watchdog and reconnect the notification listener. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        RebindJob.schedule(c);
        RebindJob.heal(c, true, "Phone restarted / app updated");
    }
}
