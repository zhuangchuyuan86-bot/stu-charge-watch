package com.stu.chargewatch;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class MonitorJobService extends JobService {
    private static final int JOB_ID = 84908485;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean stopped;
    private Future<?> pending;

    static boolean schedule(Context context) {
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, MonitorJobService.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(TimeUnit.MINUTES.toMillis(15), TimeUnit.MINUTES.toMillis(5))
            .setPersisted(true).build();
        return context.getSystemService(JobScheduler.class).schedule(job) == JobScheduler.RESULT_SUCCESS;
    }
    static void cancel(Context context) { context.getSystemService(JobScheduler.class).cancel(JOB_ID); }

    @Override public boolean onStartJob(JobParameters params) {
        if (!Settings.watch(this) || !Notifications.allowed(this)) return false;
        stopped = false;
        pending = executor.submit(() -> {
            Provider provider = Provider.get(this);
            List<Station> before = provider.snapshots();
            List<Station> after = provider.refreshAll();
            if (!stopped) {
                Notifications.report(this, before, after);
                jobFinished(params, false);
            }
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        stopped = true;
        if (pending != null) pending.cancel(true);
        return Settings.watch(this);
    }
    @Override public void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
}
