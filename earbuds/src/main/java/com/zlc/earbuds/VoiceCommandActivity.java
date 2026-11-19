package com.zlc.earbuds;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.Calendar;
import java.util.Locale;

/**
 * 语音控制: long-press the earbud (set to "voice assistant") or tap 试一试 in the app, hear a beep,
 * say a command into the earbud microphone: 下一首 / 上一首 / 暂停 / 播放 / 大声点 / 小声点 /
 * 降噪 / 通透 / 关闭降噪 / 电量 / 几点了 / 开始翻译 / 停止翻译 / 全景声 / 录音室 / 智能音效 / 关闭音效.
 *
 * The earbud microphone only works in Bluetooth call mode, which interrupts music, so it is
 * switched on just for the few seconds of the command and released before acting.
 */
public class VoiceCommandActivity extends Activity {
    private static final int RATE = 16000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private AudioManager am;
    private TextView title, heard;
    private View dot;
    private volatile boolean done;
    private boolean usedEarMic;
    private String lang = "zh-CN";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        try {
            JSONObject c = new JSONObject(getSharedPreferences(LiveService.PREFS, MODE_PRIVATE).getString("cfg", "{}"));
            lang = c.optString("meAsr", "zh-CN");
        } catch (Exception ignored) { }
        buildUi();
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            show("需要麦克风权限", "请先打开耳机翻译 App 允许麦克风");
            finishSoon(2500);
            return;
        }
        new Thread(this::run, "voice-cmd").start();
    }

    // ---------------------------------------------------------------- listening

    private void run() {
        boolean ear = earbudsConnected();
        if (ear) usedEarMic = startEarMic();
        SystemClock.sleep(usedEarMic ? 700 : 150); // Bluetooth call audio takes a moment to open
        beep(usedEarMic);
        ui.post(() -> show("请说…", usedEarMic ? "正在用耳机麦克风听" : "正在用手机麦克风听"));
        short[] pcm = record();
        stopEarMic();
        if (done) return;
        if (pcm == null) { say("没听到", "没有听到声音", true); return; }
        ui.post(() -> show("识别中…", ""));
        String text = recognize(pcm);
        if (text == null) { say("网络不好", "识别失败，请检查网络", true); return; }
        if (text.isEmpty()) { say("没听清", "没听清，再试一次", true); return; }
        final String t = text;
        ui.post(() -> act(t));
    }

    /** Records until 0.7 s of quiet after speech, 5 s at most, or nothing for 3 s. */
    private short[] record() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec;
        try {
            int src = usedEarMic ? MediaRecorder.AudioSource.VOICE_COMMUNICATION : MediaRecorder.AudioSource.VOICE_RECOGNITION;
            rec = new AudioRecord(src, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) { rec.release(); return null; }
            rec.startRecording();
        } catch (Throwable t) {
            return null;
        }
        int frame = RATE / 50;
        short[] buf = new short[RATE * 5];
        int len = 0, loud = 0, quiet = 0, voiced = 0;
        double noise = 150;
        boolean speaking = false;
        try {
            while (len + frame <= buf.length && !done) {
                int got = 0;
                while (got < frame) { int r = rec.read(buf, len + got, frame - got); if (r < 0) break; got += r; }
                double sum = 0;
                for (int i = len; i < len + frame; i++) sum += (double) buf[i] * buf[i];
                double rms = Math.sqrt(sum / frame);
                len += frame;
                if (!speaking) {
                    if (rms < Math.max(noise * 2.5, 250)) noise = noise * 0.9 + rms * 0.1;
                    loud = rms > Math.max(noise * 2.5, 250) ? loud + 1 : 0;
                    if (loud >= 3) { speaking = true; voiced = loud; }
                    else if (len > RATE * 3) return null;
                } else {
                    if (rms < Math.max(noise * 1.8, 180)) quiet++; else { quiet = 0; voiced++; }
                    if (quiet >= 35) break;
                }
            }
        } finally {
            try { rec.stop(); } catch (Throwable ignored) { }
            rec.release();
        }
        if (!speaking || voiced < 8) return null;
        short[] out = new short[len];
        System.arraycopy(buf, 0, out, 0, len);
        return out;
    }

    private String recognize(short[] pcm) {
        try {
            String t = CloudAsr.recognizeOnce(Flac.encode(pcm, pcm.length, RATE), lang);
            return t == null ? null : t.trim();
        } catch (Throwable e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- the earbud microphone (Bluetooth call mode)

    @SuppressWarnings("deprecation")
    private boolean startEarMic() {
        try {
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            if (Build.VERSION.SDK_INT >= 31) {
                for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                    int t = d.getType();
                    if (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                        if (am.setCommunicationDevice(d)) return true;
                    }
                }
            } else if (am.isBluetoothScoAvailableOffCall()) {
                am.startBluetoothSco();
                am.setBluetoothScoOn(true);
                for (int i = 0; i < 30 && !am.isBluetoothScoOn(); i++) SystemClock.sleep(100);
                return true;
            }
        } catch (Throwable ignored) { }
        am.setMode(AudioManager.MODE_NORMAL);
        return false;
    }

    @SuppressWarnings("deprecation")
    private void stopEarMic() {
        if (!usedEarMic) return;
        try {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice();
            else { am.setBluetoothScoOn(false); am.stopBluetoothSco(); }
            am.setMode(AudioManager.MODE_NORMAL);
        } catch (Throwable ignored) { }
    }

    private boolean earbudsConnected() {
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    || (Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_HEADSET)) return true;
        }
        return false;
    }

    /** Short rising "ding" so you know when to speak. */
    private void beep(boolean call) {
        try {
            int n = RATE / 6;
            short[] s = new short[n];
            for (int i = 0; i < n; i++) {
                double f = i < n / 2 ? 880 : 1320, env = Math.min(1, Math.min(i, n - i) / 400.0);
                s[i] = (short) (Math.sin(2 * Math.PI * f * i / RATE) * 9000 * env);
            }
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(call ? AudioAttributes.USAGE_VOICE_COMMUNICATION : AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(n * 2).setTransferMode(AudioTrack.MODE_STATIC).build();
            t.write(s, 0, n);
            t.play();
            SystemClock.sleep(n * 1000L / RATE + 60);
            t.release();
        } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- commands

    static String command(String raw) {
        String t = raw.toLowerCase(Locale.ROOT).replaceAll("[\\s，。,.!！?？]", "");
        if (has(t, "停止翻译", "关闭翻译", "结束翻译", "关掉翻译", "stoptranslat")) return "tr_off";
        if (has(t, "开始翻译", "打开翻译", "实时翻译", "同声传译", "翻译", "translat")) return "tr_on";
        if (has(t, "下一首", "下一曲", "下首", "切歌", "换一首", "换首", "next")) return "next";
        if (has(t, "上一首", "上一曲", "上首", "previous", "lastsong", "goback")) return "prev";
        if (has(t, "关闭降噪", "关掉降噪", "关降噪", "降噪关", "noisecancellingoff", "ancoff")) return "anc_off";
        if (has(t, "通透", "透明", "环境音", "transparen", "ambient")) return "transparency";
        if (has(t, "降噪", "noisecancel", "anc")) return "anc";
        if (has(t, "关闭音效", "关掉音效", "原声", "无损")) return "fx_lossless";
        if (has(t, "全景声", "空间音", "环绕", "spatial", "surround")) return "fx_spatial";
        if (has(t, "录音室", "studio")) return "fx_studio";
        if (has(t, "智能音效", "智能")) return "fx_smart";
        if (has(t, "暂停", "停一下", "停止", "别放", "不要放", "pause", "stop")) return "pause";
        if (has(t, "播放", "继续", "接着放", "放歌", "放音乐", "play", "resume")) return "play";
        if (has(t, "大声", "调大", "大一点", "大点", "加大", "增大", "音量加", "音量高", "louder", "volumeup")) return "vol_up";
        if (has(t, "小声", "调小", "小一点", "小点", "减小", "降低音量", "音量减", "音量低", "quieter", "volumedown")) return "vol_down";
        if (has(t, "电量", "多少电", "电池", "没电", "battery")) return "battery";
        if (has(t, "几点", "时间", "time")) return "time";
        return null;
    }

    private static boolean has(String t, String... words) {
        for (String w : words) if (t.contains(w)) return true;
        return false;
    }

    private void act(String text) {
        String c = command(text);
        heard.setText("“" + text + "”");
        if (c == null) { say("没听懂", "可以说：下一首、暂停、大声点、降噪、电量…", true); return; }
        switch (c) {
            case "next": media(KeyEvent.KEYCODE_MEDIA_NEXT); done("下一首", null); break;
            case "prev": media(KeyEvent.KEYCODE_MEDIA_PREVIOUS); done("上一首", null); break;
            case "pause": media(KeyEvent.KEYCODE_MEDIA_PAUSE); done("已暂停", null); break;
            case "play": media(KeyEvent.KEYCODE_MEDIA_PLAY); done("继续播放", null); break;
            case "vol_up": volume(true); done("音量调大", null); break;
            case "vol_down": volume(false); done("音量调小", null); break;
            case "anc": noise("anc", "降噪已开启"); break;
            case "transparency": noise("transparency", "通透模式"); break;
            case "anc_off": noise("off", "降噪已关闭"); break;
            case "battery": battery(); break;
            case "time": {
                Calendar k = Calendar.getInstance();
                String s = String.format(Locale.CHINA, "现在%d点%d分", k.get(Calendar.HOUR_OF_DAY), k.get(Calendar.MINUTE));
                say(s, s, false);
                break;
            }
            case "tr_on": LiveService.send(this, LiveService.ACT_MANUAL_ON); say("开始翻译", "实时翻译已开始", false); break;
            case "tr_off": if (LiveService.instance != null) LiveService.send(this, LiveService.ACT_MANUAL_OFF); say("翻译已停止", "实时翻译已停止", false); break;
            case "fx_spatial": SoundService.set(this, "spatial"); say("已开启全景声", "超清 AUDIO 全景声", false); break;
            case "fx_studio": SoundService.set(this, "studio"); say("已开启录音室音质", "超清录音室音质", false); break;
            case "fx_smart": SoundService.set(this, "smart"); say("已开启智能音效", "超清智能音效", false); break;
            case "fx_lossless": SoundService.set(this, "lossless"); say("已关闭音效", "无损：原声输出", false); break;
            default:
        }
    }

    private void media(int key) {
        // give the music app a moment to get its sound back from call mode
        ui.postDelayed(() -> {
            long now = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0));
        }, usedEarMic ? 500 : 50);
    }

    private void volume(boolean up) {
        for (int i = 0; i < 2; i++)
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
    }

    /** Uses the app's connection to the earbuds, or opens one for this command. */
    private void noise(String mode, String ok) {
        show(ok, "正在切换…");
        new Thread(() -> {
            OppoBuds b = budsReady();
            boolean done = b != null && b.setNoise(mode);
            ui.post(() -> say(done ? ok : "切换失败", done ? ok : "连不上耳机控制，请在系统耳机页切换", !done));
        }).start();
    }

    private void battery() {
        show("电量", "正在读取…");
        new Thread(() -> {
            OppoBuds b = budsReady();
            String msg = null;
            if (b != null) {
                b.refresh();
                SystemClock.sleep(700);
                try {
                    JSONObject o = new JSONObject(b.json());
                    int l = o.optInt("left", -1), r = o.optInt("right", -1), c = o.optInt("case", -1);
                    if (l >= 0 || r >= 0) {
                        msg = "左耳百分之" + Math.max(l, 0) + "，右耳百分之" + Math.max(r, 0) + (c > 0 ? "，充电盒百分之" + c : "");
                    }
                } catch (Exception ignored) { }
            }
            final String m = msg;
            ui.post(() -> say(m != null ? m : "读不到电量", m != null ? m : "连不上耳机控制", m == null));
        }).start();
    }

    @SuppressLint("MissingPermission")
    private OppoBuds budsReady() {
        OppoBuds b = OppoBuds.shared;
        if (b != null && b.isConnected()) return b;
        if (b == null) { b = new OppoBuds(j -> { }); OppoBuds.shared = b; }
        try {
            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter ad = bm.getAdapter();
            for (BluetoothDevice d : ad.getBondedDevices()) {
                boolean connected = false;
                try { Method m = d.getClass().getMethod("isConnected"); connected = (Boolean) m.invoke(d); } catch (Throwable ignored) { }
                if (!connected) continue;
                b.connect(d);
                for (int i = 0; i < 40 && !b.isConnected(); i++) SystemClock.sleep(100);
                if (b.isConnected()) { SystemClock.sleep(300); return b; }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    // ---------------------------------------------------------------- feedback

    private void done(String titleText, String sub) {
        show(titleText, sub == null ? "" : sub);
        finishSoon(1200);
    }

    /** Shows and says a short answer (into the earbuds), then closes. */
    private void say(String speak, String text, boolean failed) {
        ui.post(() -> {
            show(failed ? "😕 " + speak : speak, text.equals(speak) ? "" : text);
            ui.postDelayed(() -> Voice.get(this).speak("vc" + SystemClock.uptimeMillis(), speak, ttsLang(), "ear", 1.05f,
                    (id, how) -> finishSoon(400)), usedEarMic ? 400 : 0);
            finishSoon(6000);
        });
    }

    private String ttsLang() {
        try {
            JSONObject c = new JSONObject(getSharedPreferences(LiveService.PREFS, MODE_PRIVATE).getString("cfg", "{}"));
            String m = c.optString("meTts", "zh-CN");
            return m.startsWith("zh") ? m : "zh-CN";
        } catch (Exception e) {
            return "zh-CN";
        }
    }

    private void finishSoon(long ms) {
        ui.postDelayed(() -> { if (!isFinishing()) finish(); }, ms);
    }

    private void show(String t, String s) {
        title.setText(t);
        heard.setText(s);
    }

    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    private void buildUi() {
        Window w = getWindow();
        w.setBackgroundDrawableResource(android.R.color.transparent);
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(0.25f);
        FrameLayout root = new FrameLayout(this);
        root.setOnClickListener(v -> { done = true; stopEarMic(); finish(); });
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(24), dp(22), dp(24), dp(24));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(240, 255, 255, 255));
        bg.setCornerRadius(dp(28));
        card.setBackground(bg);
        card.setElevation(dp(12));
        dot = new View(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF0A84FF, 0xFFBF5AF2});
        g.setShape(GradientDrawable.OVAL);
        dot.setBackground(g);
        card.addView(dot, new LinearLayout.LayoutParams(dp(56), dp(56)));
        dot.animate().scaleX(1.15f).scaleY(1.15f).setDuration(600).withEndAction(this::pulse).start();
        title = new TextView(this);
        title.setTextColor(Color.BLACK);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(14), 0, 0);
        title.setText("准备中…");
        card.addView(title);
        heard = new TextView(this);
        heard.setTextColor(0xFF6E6E73);
        heard.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        heard.setGravity(Gravity.CENTER);
        heard.setPadding(0, dp(6), 0, 0);
        card.addView(heard);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        lp.setMargins(dp(14), 0, dp(14), dp(28));
        root.addView(card, lp);
        card.setOnClickListener(v -> { });
        setContentView(root);
    }

    private void pulse() {
        if (isFinishing() || dot == null) return;
        float s = dot.getScaleX() > 1.05f ? 0.92f : 1.15f;
        dot.animate().scaleX(s).scaleY(s).setDuration(600).withEndAction(this::pulse).start();
    }

    @Override
    protected void onDestroy() {
        done = true;
        stopEarMic();
        super.onDestroy();
    }
}
