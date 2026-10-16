package com.deskalert.trigger;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.service.notification.NotificationListenerService;

/** Watchdog: every ~15 min (and after reboot/update) asks Android to reconnect the notification listener if it was stopped. */
public class RebindJob extends JobService {
    private static final int JOB_ID = 4242;

    static void rebind(Context c) {
        try { NotificationListenerService.class.getMethod("requestRebind", ComponentName.class).invoke(null, new ComponentName(c, NotifService.class)); }
        catch (Exception ignored) {}
        Prefs.put(c, "watchdog_ts", String.valueOf(System.currentTimeMillis()));
    }

    static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            for (JobInfo ji : js.getAllPendingJobs()) if (ji.getId() == JOB_ID) return;     // already scheduled
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, RebindJob.class))
                    .setPeriodic(15 * 60 * 1000L).setPersisted(true).build());
        } catch (Exception ignored) {}
    }

    @Override public boolean onStartJob(JobParameters params) { rebind(this); jobFinished(params, false); return false; }
    @Override public boolean onStopJob(JobParameters params) { return false; }
}
