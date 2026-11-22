package com.zlc.subs;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** Keeps one alarm per upcoming renewal reminder; the page sends the full list each time it changes. */
final class Reminders {
    private static final String PREFS = "reminders";

    private Reminders() {
    }

    static void schedule(Context ctx, String json) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().putString("list", json == null ? "[]" : json).apply();
        apply(ctx);
    }

    /** Cancels the previous alarms and sets one for every reminder that is still in the future. */
    static void apply(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        int old = p.getInt("count", 0);
        for (int i = 0; i < old; i++) {
            PendingIntent pi = PendingIntent.getBroadcast(ctx, i, new Intent(ctx, ReminderReceiver.class),
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (pi != null) {
                am.cancel(pi);
                pi.cancel();
            }
        }
        int n = 0;
        long now = System.currentTimeMillis();
        try {
            JSONArray a = new JSONArray(p.getString("list", "[]"));
            for (int i = 0; i < a.length() && n < 64; i++) {
                JSONObject o = a.getJSONObject(i);
                long at = o.optLong("at", 0);
                if (at <= now) continue;
                Intent it = new Intent(ctx, ReminderReceiver.class)
                        .putExtra("key", o.optString("key"))
                        .putExtra("title", o.optString("title"))
                        .putExtra("text", o.optString("text"));
                PendingIntent pi = PendingIntent.getBroadcast(ctx, n, it,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                n++;
            }
        } catch (Exception ignored) {
        }
        p.edit().putInt("count", n).apply();
    }
}
