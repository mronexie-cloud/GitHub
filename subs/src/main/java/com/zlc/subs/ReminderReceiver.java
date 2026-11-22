package com.zlc.subs;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Shows one renewal reminder notification. */
public class ReminderReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "renew";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "续费提醒", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("订阅到期前提醒你");
            nm.createNotificationChannel(ch);
        }
        String key = intent.getStringExtra("key");
        PendingIntent open = PendingIntent.getActivity(ctx, 0,
                new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CHANNEL)
                : new Notification.Builder(ctx);
        b.setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(intent.getStringExtra("title"))
                .setContentText(intent.getStringExtra("text"))
                .setStyle(new Notification.BigTextStyle().bigText(intent.getStringExtra("text")))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setColor(0xFF0A84FF);
        try {
            nm.notify(key == null ? 1 : key.hashCode(), b.build());
        } catch (SecurityException ignored) {
            // notifications turned off
        }
    }
}
