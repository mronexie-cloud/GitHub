package com.zlc.earbuds;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Earbuds connected while 自动翻译 is on but its service isn't running (e.g. after a restart):
 * start it when Android allows, otherwise show a one-tap notification to turn it back on
 * (Android only lets an app start using the microphone while the user is interacting with it).
 */
public class BtReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!LiveService.autoEnabled(c) || LiveService.instance != null) return;
        if (Build.VERSION.SDK_INT < 31) {
            try { LiveService.send(c, LiveService.ACT_AUTO_ON); return; } catch (Exception ignored) { }
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("connect", "耳机连接提醒", NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, 2, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        @SuppressWarnings("deprecation")
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, "connect") : new Notification.Builder(c);
        b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("耳机已连接")
                .setContentText("点一下开启自动翻译")
                .setAutoCancel(true)
                .setContentIntent(pi);
        try { nm.notify(78, b.build()); } catch (Exception ignored) { }
    }
}
