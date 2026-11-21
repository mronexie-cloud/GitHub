package com.zlc.earbuds;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 耳机翻译: a HeyMelody / AirPods-style earbud page plus live conversation translation.
 * The UI is assets/index.html. Translation itself runs in {@link LiveService} so it keeps
 * working in the background and can start by itself when the earbuds are put in.
 */
public class MainActivity extends Activity {
    private static final int PERMS = 7, CAPTURE = 9;

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private AudioManager am;

    // Bluetooth
    private BluetoothAdapter adapter;
    private BluetoothProfile a2dp, headset;
    private final Map<String, Integer> batteryFromBroadcast = new HashMap<>();
    private BroadcastReceiver receiver;
    private OppoBuds buds;
    private long budsFailedAt;
    private String budsFailedAddr;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        Voice.get(this); // start the text-to-speech engine early
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pushDevices();
                js("window.onLive&&window.onLive(" + LiveService.state() + ")");
            }
        });
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");

        LiveService.sink = json -> js("window.onLive&&window.onLive(" + json + ")");
        SoundService.sink = json -> js("window.onSound&&window.onSound(" + json + ")");
        MovieService.sink = json -> js("window.onMovie&&window.onMovie(" + json + ")");
        // keep the chosen sound effect on
        if (SoundService.needsService(SoundService.saved(this)) && SoundService.instance == null) {
            try { SoundService.set(this, SoundService.saved(this)); } catch (Exception ignored) { }
        }
        OppoBuds.Listener bl = json -> js("window.onBuds&&window.onBuds(" + json + ")");
        if (OppoBuds.shared != null) { buds = OppoBuds.shared; buds.setListener(bl); }
        else { buds = new OppoBuds(bl); OppoBuds.shared = buds; }

        askPermissions();
        setupBluetooth();
        ui.postDelayed(budsPoll, 60000);
        // 自动翻译 was on: make sure the service is running (the app is in front, so it may use the microphone)
        if (LiveService.autoEnabled(this) && LiveService.instance == null && micAllowed()) {
            try { LiveService.send(this, LiveService.ACT_AUTO_ON); } catch (Exception ignored) { }
        }
    }

    private boolean micAllowed() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** Re-reads battery and noise mode every minute. */
    private final Runnable budsPoll = new Runnable() {
        @Override public void run() {
            if (buds != null && buds.isConnected()) new Thread(buds::refresh).start();
            ui.postDelayed(this, 60000);
        }
    };

    /** Opens the earbuds' control channel for the connected OnePlus/OPPO/realme earbuds. */
    @SuppressLint("MissingPermission")
    private void syncBuds(List<BluetoothDevice> connected) {
        if (buds == null) return;
        BluetoothDevice pick = null;
        for (BluetoothDevice d : connected) {
            String n = null;
            try { n = d.getName(); } catch (Exception ignored) { }
            n = n == null ? "" : n.toLowerCase(Locale.ROOT);
            if (n.contains("oneplus") || n.contains("oppo") || n.contains("enco") || n.contains("realme") || n.contains("buds")) { pick = d; break; }
        }
        if (pick == null && !connected.isEmpty()) pick = connected.get(0);
        if (pick == null) { if (buds.address() != null) buds.close(); return; }
        if (pick.getAddress().equals(buds.address()) && (buds.isConnected() || System.currentTimeMillis() - budsFailedAt < 30000)) return;
        // don't hammer earbuds that refused the control channel
        if (pick.getAddress().equals(budsFailedAddr) && System.currentTimeMillis() - budsFailedAt < 30000) return;
        budsFailedAddr = pick.getAddress();
        budsFailedAt = System.currentTimeMillis();
        buds.connect(pick);
    }

    // ---------------------------------------------------------------- permissions

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), PERMS);
    }

    private boolean btAllowed() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == PERMS) {
            setupBluetooth();
            pushDevices();
            js("window.onPerms&&window.onPerms()");
        }
    }

    // ---------------------------------------------------------------- Bluetooth earbuds

    private void setupBluetooth() {
        if (adapter == null) {
            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            adapter = bm != null ? bm.getAdapter() : null;
        }
        if (adapter == null) return;
        BluetoothProfile.ServiceListener l = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.A2DP) a2dp = proxy;
                else if (profile == BluetoothProfile.HEADSET) headset = proxy;
                pushDevices();
            }

            @Override
            public void onServiceDisconnected(int profile) {
                if (profile == BluetoothProfile.A2DP) a2dp = null;
                else if (profile == BluetoothProfile.HEADSET) headset = null;
                pushDevices();
            }
        };
        try {
            if (a2dp == null) adapter.getProfileProxy(this, l, BluetoothProfile.A2DP);
            if (headset == null) adapter.getProfileProxy(this, l, BluetoothProfile.HEADSET);
        } catch (Exception ignored) {
        }
        if (receiver == null) {
            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    String a = i.getAction();
                    if ("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED".equals(a)) {
                        BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                        int lvl = i.getIntExtra("android.bluetooth.device.extra.BATTERY_LEVEL", -1);
                        if (d != null && lvl >= 0) batteryFromBroadcast.put(d.getAddress(), lvl);
                    }
                    if ("android.media.VOLUME_CHANGED_ACTION".equals(a)) {
                        js("window.onVolume&&window.onVolume(" + volumeJson() + ")");
                        return;
                    }
                    ui.removeCallbacks(pushRunnable);
                    ui.postDelayed(pushRunnable, 500);
                }
            };
            IntentFilter f = new IntentFilter();
            f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
            f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            f.addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED");
            f.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
            f.addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED");
            f.addAction("android.media.VOLUME_CHANGED_ACTION");
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
            else registerReceiver(receiver, f);
        }
    }

    private final Runnable pushRunnable = this::pushDevices;

    private static int batteryOf(BluetoothDevice d) {
        try {
            Method m = BluetoothDevice.class.getMethod("getBatteryLevel");
            Object v = m.invoke(d);
            return v instanceof Integer ? (Integer) v : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    @SuppressLint("MissingPermission")
    private String devicesJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("perm", btAllowed());
            o.put("mic", checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
            o.put("bt", adapter != null && btAllowed() && adapter.isEnabled());
            JSONArray arr = new JSONArray();
            Map<String, JSONObject> seen = new HashMap<>();
            List<BluetoothDevice> connected = new ArrayList<>();
            if (btAllowed()) {
                for (int p = 0; p < 2; p++) {
                    BluetoothProfile prof = p == 0 ? a2dp : headset;
                    if (prof == null) continue;
                    List<BluetoothDevice> list;
                    try { list = prof.getConnectedDevices(); } catch (Exception e) { continue; }
                    for (BluetoothDevice d : list) {
                        JSONObject x = seen.get(d.getAddress());
                        if (x == null) {
                            x = new JSONObject();
                            String name = null;
                            try { name = d.getName(); } catch (Exception ignored) { }
                            x.put("name", name == null ? "蓝牙耳机" : name);
                            x.put("addr", d.getAddress());
                            int b = batteryOf(d);
                            if (b < 0 && batteryFromBroadcast.containsKey(d.getAddress())) b = batteryFromBroadcast.get(d.getAddress());
                            x.put("battery", b);
                            seen.put(d.getAddress(), x);
                            arr.put(x);
                            connected.add(d);
                        }
                        x.put(p == 0 ? "a2dp" : "hfp", true);
                    }
                }
            }
            o.put("devices", arr);
            if (btAllowed()) { final List<BluetoothDevice> c = connected; ui.post(() -> syncBuds(c)); }
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    private void pushDevices() {
        js("window.onDevices&&window.onDevices(" + devicesJson() + ")");
    }

    private String volumeJson() {
        return "{\"v\":" + am.getStreamVolume(AudioManager.STREAM_MUSIC) + ",\"max\":" + am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) + "}";
    }

    // ---------------------------------------------------------------- helpers

    private void js(String code) {
        ui.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    @Override
    protected void onStart() {
        super.onStart();
        pushDevices();
        js("window.onLive&&window.onLive(" + LiveService.state() + ");window.onHistory&&window.onHistory()");
    }

    /** The user answered the screen-recording prompt for 视频同传. */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            js("window.onMovie&&window.onMovie({t:'movie_error',what:'denied'})");
            return;
        }
        Intent i = new Intent(this, MovieService.class)
                .putExtra(MovieService.EXTRA_CODE, resultCode)
                .putExtra(MovieService.EXTRA_DATA, data);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Exception e) {
            js("window.onMovie&&window.onMovie({t:'movie_error',what:'capture'})");
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onAndroidBack ? window.onAndroidBack() : false", v -> {
            if ("true".equals(v)) return;
            moveTaskToBack(true);
        });
    }

    @Override
    protected void onDestroy() {
        LiveService.sink = null;
        SoundService.sink = null;
        MovieService.sink = null;
        ui.removeCallbacks(budsPoll);
        if (buds != null) { buds.close(); if (OppoBuds.shared == buds) OppoBuds.shared = null; }
        if (receiver != null) { try { unregisterReceiver(receiver); } catch (Exception ignored) { } }
        if (adapter != null) {
            if (a2dp != null) adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
            if (headset != null) adapter.closeProfileProxy(BluetoothProfile.HEADSET, headset);
        }
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }

    class Bridge {
        @JavascriptInterface public String devices() { return devicesJson(); }
        @JavascriptInterface public void refreshDevices() { ui.post(MainActivity.this::pushDevices); }
        @JavascriptInterface public boolean ttsHas(String lang) { return Voice.get(MainActivity.this).has(lang); }
        @JavascriptInterface public boolean earbuds() { return Voice.get(MainActivity.this).earbudsConnected(); }

        // earbud control
        @JavascriptInterface public String buds() { return buds.json(); }
        @JavascriptInterface public boolean budsNoise(String mode) { return buds.setNoise(mode); }
        @JavascriptInterface public boolean budsGame(boolean on) { return buds.setGame(on); }
        @JavascriptInterface public boolean budsFind(boolean on) { return buds.find(on); }
        @JavascriptInterface public void budsRefresh() { new Thread(buds::refresh).start(); }
        @JavascriptInterface public void budsReconnect() {
            ui.post(() -> { budsFailedAddr = null; buds.close(); pushDevices(); });
        }

        // sound effects (runs in SoundService): off, spatial, studio, lossless, smart
        @JavascriptInterface public String soundState() { return SoundService.state(MainActivity.this); }
        @JavascriptInterface public void soundSet(String preset) {
            ui.post(() -> {
                try { SoundService.set(MainActivity.this, preset); } catch (Exception ignored) { }
                ui.postDelayed(() -> js("window.onSound&&window.onSound(" + SoundService.state(MainActivity.this) + ")"), 400);
            });
        }

        // 视频同传 (runs in MovieService)
        @JavascriptInterface public String movieState() { return MovieService.state(); }
        @JavascriptInterface public boolean overlayOk() { return MovieService.overlayAllowed(MainActivity.this); }
        @JavascriptInterface public void openOverlaySettings() {
            ui.post(() -> {
                try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); }
                catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) { } }
            });
        }
        /** Returns "ok" (prompt shown), "old" (Android below 10), "mic" (no microphone permission). */
        @JavascriptInterface public String movieStart(String cfgJson) {
            getSharedPreferences(MovieService.PREFS, MODE_PRIVATE).edit().putString("cfg", cfgJson).apply();
            if (Build.VERSION.SDK_INT < 29) return "old";
            if (!micAllowed()) { ui.post(MainActivity.this::askPermissions); return "mic"; }
            ui.post(() -> {
                android.media.projection.MediaProjectionManager mpm =
                        (android.media.projection.MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                Intent ask = Build.VERSION.SDK_INT >= 34
                        ? mpm.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
                        : mpm.createScreenCaptureIntent();
                try { startActivityForResult(ask, CAPTURE); } catch (Exception ignored) { }
            });
            return "ok";
        }
        @JavascriptInterface public void movieStop() {
            ui.post(() -> { try { startService(new Intent(MainActivity.this, MovieService.class).setAction(MovieService.ACT_STOP)); } catch (Exception ignored) { } });
        }

        // 语音控制
        @JavascriptInterface public void voiceTry() {
            ui.post(() -> { try { startActivity(new Intent(MainActivity.this, VoiceCommandActivity.class)); } catch (Exception ignored) { } });
        }
        @JavascriptInterface public void openDefaultApps() {
            ui.post(() -> {
                try { startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)); }
                catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)); } catch (Exception ignored) { } }
            });
        }

        // live translation (runs in LiveService)
        @JavascriptInterface public String liveState() { return LiveService.state(); }
        @JavascriptInterface public void liveConfig(String json) {
            getSharedPreferences(LiveService.PREFS, MODE_PRIVATE).edit().putString("cfg", json).apply();
            if (LiveService.instance != null) ui.post(() -> LiveService.send(MainActivity.this, LiveService.ACT_CONFIG));
        }
        /** action: manual_on, manual_off, auto_on, auto_off. Returns false when the microphone isn't allowed. */
        @JavascriptInterface public boolean live(String action) {
            if (!micAllowed() && (action.endsWith("_on"))) { ui.post(MainActivity.this::askPermissions); return false; }
            if (action.endsWith("_off") && LiveService.instance == null) {
                if ("auto_off".equals(action)) getSharedPreferences(LiveService.PREFS, MODE_PRIVATE).edit().putBoolean("auto", false).apply();
                return true;
            }
            ui.post(() -> { try { LiveService.send(MainActivity.this, action); } catch (Exception ignored) { } });
            return true;
        }
        @JavascriptInterface public boolean autoEnabled() { return LiveService.autoEnabled(MainActivity.this); }
        @JavascriptInterface public String history() { return LiveService.historyJson(MainActivity.this); }
        @JavascriptInterface public void saveHistory(String json) { ui.post(() -> LiveService.writeHistory(MainActivity.this, json)); }

        @JavascriptInterface public void speak(String id, String text, String lang, String route, float rate) {
            ui.post(() -> Voice.get(MainActivity.this).speak(id, text, lang, route, rate,
                    (sid, how) -> js("window.onSpeak&&window.onSpeak(" + JSONObject.quote(how) + "," + JSONObject.quote(sid) + ")")));
        }
        @JavascriptInterface public void stopSpeak() { ui.post(() -> Voice.get(MainActivity.this).stop(true)); }

        @JavascriptInterface public String volume() { return volumeJson(); }
        @JavascriptInterface public void setVolume(int v) {
            try { am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0); } catch (Exception ignored) { }
        }

        @JavascriptInterface public void vibrate(int ms) {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(ms);
        }

        @JavascriptInterface public boolean batteryOk() {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        }

        @SuppressLint("BatteryLife")
        @JavascriptInterface public void askBattery() {
            ui.post(() -> {
                try { startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))); }
                catch (Exception e) {
                    try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) { }
                }
            });
        }

        @JavascriptInterface public void openBluetooth() {
            ui.post(() -> { try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); } catch (Exception ignored) { } });
        }

        @JavascriptInterface public void openAppSettings() {
            ui.post(() -> {
                try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
                catch (Exception ignored) { }
            });
        }

        @JavascriptInterface public void openTtsSettings() {
            ui.post(() -> {
                try { startActivity(new Intent("com.android.settings.TTS_SETTINGS")); }
                catch (Exception e) {
                    try { startActivity(new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)); } catch (Exception ignored) { }
                }
            });
        }

        @JavascriptInterface public void openUrl(String url) {
            if (url == null || !(url.startsWith("https://") || url.startsWith("market://"))) return;
            ui.post(() -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) { } });
        }

        @JavascriptInterface public void askPermissions() { ui.post(MainActivity.this::askPermissions); }

        /** Native GET used when the page cannot reach a translation service with fetch(). Result comes back via window.__httpCb. */
        @JavascriptInterface
        public void httpGet(final String id, final String url) {
            if (url == null || !url.startsWith("https://")) return;
            new Thread(() -> {
                String body = null;
                try { body = Translator.get(url); } catch (Exception ignored) { }
                js("window.__httpCb&&window.__httpCb(" + JSONObject.quote(id) + "," + (body == null ? "null" : JSONObject.quote(body)) + ")");
            }).start();
        }
    }
}
