package com.zlc.deathclock;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Background check: sends the yearly email when its day has come. */
public class ReminderJob extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            boolean ok = Reminder.runIfDue(getApplicationContext());
            jobFinished(params, !ok); // retry with back-off if sending failed
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
