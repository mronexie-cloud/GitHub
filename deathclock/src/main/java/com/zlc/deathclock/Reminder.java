package com.zlc.deathclock;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import org.json.JSONObject;

import java.io.IOException;
import java.net.Socket;
import java.util.TimeZone;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Settings, the yearly "is it due?" check, and sending through Gmail. */
final class Reminder {
    private Reminder() { }

    static final String HOST = "smtp.gmail.com";
    static final int PORT = 465;
    private static final int JOB_ID = 1;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("deathclock", Context.MODE_PRIVATE);
    }

    /** Everything the page needs, without the App Password itself. */
    static JSONObject publicConfig(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        JSONObject o = new JSONObject();
        o.put("configured", p.getLong("setupAt", 0) > 0);
        o.put("age", p.getInt("age", 0));
        o.put("deathAge", p.getInt("deathAge", 80));
        o.put("setupAt", p.getLong("setupAt", 0));
        o.put("email", p.getString("email", ""));
        o.put("to", p.getString("to", ""));
        o.put("hasPass", !p.getString("pass", "").isEmpty());
        o.put("month", p.getInt("month", 0));
        o.put("day", p.getInt("day", 0));
        o.put("note", p.getString("note", ""));
        o.put("enabled", p.getBoolean("enabled", true));
        o.put("lastSentYear", p.getInt("lastSentYear", 0));
        o.put("lastSentAt", p.getLong("lastSentAt", 0));
        o.put("lastResult", p.getString("lastResult", ""));
        o.put("lastTryAt", p.getLong("lastTryAt", 0));
        int m = p.getInt("month", 0), d = p.getInt("day", 0);
        if (m > 0) o.put("nextAt", LifeMath.nextReminder(System.currentTimeMillis(), p.getInt("lastSentYear", 0), m, d, TimeZone.getDefault()));
        return o;
    }

    /** Saves the settings page. Re-anchors the countdown only when the ages change. */
    static void save(Context c, JSONObject in) {
        SharedPreferences p = prefs(c);
        SharedPreferences.Editor e = p.edit();
        long now = System.currentTimeMillis();
        int age = in.optInt("age"), deathAge = in.optInt("deathAge");
        boolean agesChanged = p.getLong("setupAt", 0) == 0 || p.getInt("age", -1) != age || p.getInt("deathAge", -1) != deathAge;
        if (agesChanged) {
            e.putInt("age", age).putInt("deathAge", deathAge).putLong("setupAt", now);
        }
        int month = in.optInt("month"), day = in.optInt("day");
        boolean dateChanged = p.getInt("month", -1) != month || p.getInt("day", -1) != day;
        e.putInt("month", month).putInt("day", day);
        if (dateChanged) e.putInt("lastSentYear", LifeMath.initialLastSentYear(now, month, day, TimeZone.getDefault()));
        e.putString("email", in.optString("email").trim());
        e.putString("to", in.optString("to").trim());
        String pass = in.optString("pass", "");
        if (!pass.isEmpty()) e.putString("pass", pass.replace(" ", "").trim());
        e.putString("note", in.optString("note"));
        e.putBoolean("enabled", in.optBoolean("enabled", true));
        e.apply();
    }

    static Socket openSsl(String host, int port) throws IOException {
        SSLSocket s = (SSLSocket) SSLSocketFactory.getDefault().createSocket(host, port);
        s.setSoTimeout(30_000);
        s.startHandshake();
        HostnameVerifier v = HttpsURLConnection.getDefaultHostnameVerifier();
        if (!v.verify(host, s.getSession())) {
            s.close();
            throw new IOException("Gmail 的安全证书校验失败");
        }
        return s;
    }

    /** Sends one email now with the saved settings. Returns null on success, else a message. */
    static String sendNow(Context c, boolean test) {
        SharedPreferences p = prefs(c);
        String email = p.getString("email", ""), pass = p.getString("pass", "");
        if (email.isEmpty() || pass.isEmpty()) return "还没有填 Gmail 邮箱和应用专用密码";
        String to = p.getString("to", "");
        if (to.isEmpty()) to = email;
        long now = System.currentTimeMillis();
        TimeZone tz = TimeZone.getDefault();
        int age = p.getInt("age", 0), deathAge = p.getInt("deathAge", 80);
        long setupAt = p.getLong("setupAt", now);
        String note = p.getString("note", "");
        String[] m = test ? EmailContent.test(now, setupAt, age, deathAge, note, tz) : EmailContent.build(now, setupAt, age, deathAge, note, tz);
        try {
            Mailer.send(Reminder::openSsl, HOST, PORT, email, pass, to, m[0], m[1], m[2]);
            return null;
        } catch (Exception ex) {
            return Mailer.explain(ex);
        }
    }

    private static boolean online(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true;
        NetworkInfo n = cm.getActiveNetworkInfo();
        return n != null && n.isConnected();
    }

    /**
     * Sends this year's email if it is due and hasn't gone out yet. Called by the background
     * job and whenever the app is opened, so a missed date (phone off) is caught up later.
     *
     * @return false if it was due but failed (the job then retries later)
     */
    static synchronized boolean runIfDue(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("enabled", true) || p.getLong("setupAt", 0) == 0) return true;
        if (p.getString("email", "").isEmpty() || p.getString("pass", "").isEmpty()) return true;
        int m = p.getInt("month", 0), d = p.getInt("day", 0);
        if (m == 0) return true;
        long now = System.currentTimeMillis();
        TimeZone tz = TimeZone.getDefault();
        if (!LifeMath.due(now, p.getInt("lastSentYear", 0), m, d, tz)) return true;
        if (!online(c)) return false;
        String err = sendNow(c, false);
        SharedPreferences.Editor e = p.edit().putLong("lastTryAt", now);
        if (err == null) {
            e.putInt("lastSentYear", LifeMath.yearOf(now, tz)).putLong("lastSentAt", now).putString("lastResult", "ok");
        } else {
            e.putString("lastResult", err);
        }
        e.apply();
        return err == null;
    }

    /** A light background check every 6 hours (only when online) keeps the yearly email on time. */
    static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        if (js.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(c, ReminderJob.class))
                .setPeriodic(6 * 60 * 60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build();
        js.schedule(job);
    }
}
