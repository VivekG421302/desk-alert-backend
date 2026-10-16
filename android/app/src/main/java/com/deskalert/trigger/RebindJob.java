package com.deskalert.trigger;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.service.notification.NotificationListenerService;

/** Watchdog: every ~15 min (and after reboot/update) asks Android to reconnect the notification listener if it was stopped. */
public class RebindJob extends JobService {
    private static final int JOB_ID = 4242;

    private static long ts(Context c, String k) { try { return Long.parseLong(Prefs.get(c, k, "0")); } catch (Exception e) { return 0; } }

    /** Asks Android to reconnect the listener (does nothing if it is already connected). */
    static void rebind(Context c) {
        try { NotificationListenerService.class.getMethod("requestRebind", ComponentName.class).invoke(null, new ComponentName(c, NotifService.class)); }
        catch (Exception ignored) {}
        Prefs.put(c, "watchdog_ts", String.valueOf(System.currentTimeMillis()));
    }

    /** Disable + re-enable the listener component: the reliable way to make Android bind it again when it is stuck. */
    static void toggleListener(Context c) {
        try {
            PackageManager pm = c.getPackageManager();
            ComponentName cn = new ComponentName(c, NotifService.class);
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            Prefs.put(c, "toggle_ts", String.valueOf(System.currentTimeMillis()));
        } catch (Exception ignored) {}
    }

    /**
     * Self-heal. force=true (app opened / reboot / update): always restart the listener.
     * Otherwise only when no notification has been seen for 30 min and the last restart was over an hour ago.
     */
    static void heal(Context c, boolean force, String why) {
        long now = System.currentTimeMillis();
        long idleMin = (now - ts(c, "any_ts")) / 60000;
        boolean stale = ts(c, "any_ts") == 0 || idleMin > 30;
        boolean due = now - ts(c, "toggle_ts") > 60 * 60000L;
        rebind(c);
        if (force || (stale && due)) {
            toggleListener(c);
            Journal.add(c, "Desk Alert", why, "WATCHDOG: listener restarted" + (force ? "" : " (no notification for " + idleMin + " min)"));
        }
    }

    static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            for (JobInfo ji : js.getAllPendingJobs()) if (ji.getId() == JOB_ID) return;     // already scheduled
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, RebindJob.class))
                    .setPeriodic(15 * 60 * 1000L).setPersisted(true).build());
        } catch (Exception ignored) {}
    }

    @Override public boolean onStartJob(JobParameters params) { heal(this, false, "Periodic check"); jobFinished(params, false); return false; }
    @Override public boolean onStopJob(JobParameters params) { return false; }
}
