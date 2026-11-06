package com.zlc.vdl;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Keeps downloads running when the app is in the background, with a progress notification. */
public class DownloadService extends Service {
    static final int ID = 11;
    static volatile boolean running = false;
    static long lastProgress = 0;

    static void refresh(Context c) {
        int n = Engine.activeCount();
        try {
            if (n > 0 && !running) {
                Intent i = new Intent(c, DownloadService.class);
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
                else c.startService(i);
            } else if (n == 0 && running) {
                c.stopService(new Intent(c, DownloadService.class));
            }
        } catch (Exception ignored) {
        }
    }

    static void progress(Context c) {
        if (!running) return;
        long now = System.currentTimeMillis();
        if (now - lastProgress < 1000) return;
        lastProgress = now;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(ID, build(c));
    }

    static Notification build(Context c) {
        int n = Engine.activeCount();
        int p = (int) Math.round(Engine.activeProgress());
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, App.CH_PROGRESS) : new Notification.Builder(c);
        b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("正在下载 " + Math.max(n, 1) + " 个")
                .setContentText(p + "%")
                .setProgress(100, p, n > 0 && p == 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi);
        return b.build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        running = true;
        Notification n = build(this);
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(ID, n);
        } catch (Exception e) {
            running = false;
            stopSelf();
        }
        if (Engine.activeCount() == 0) stopSelf();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
