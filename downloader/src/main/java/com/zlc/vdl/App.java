package com.zlc.vdl;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

public class App extends Application {
    static final String CH_PROGRESS = "progress";
    static final String CH_DONE = "done";

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel p = new NotificationChannel(CH_PROGRESS, "下载进度 Download progress", NotificationManager.IMPORTANCE_LOW);
            p.setShowBadge(false);
            nm.createNotificationChannel(p);
            nm.createNotificationChannel(new NotificationChannel(CH_DONE, "下载完成 Finished", NotificationManager.IMPORTANCE_DEFAULT));
        }
        Engine.init(this);
    }
}
