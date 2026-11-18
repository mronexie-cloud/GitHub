package com.zlc.earbuds;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.pm.ResolveInfo;
import android.speech.RecognitionService;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 耳机翻译: an AirPods-style earbud page plus live conversation translation.
 * The UI is assets/index.html; this class provides the phone features it needs
 * (Bluetooth earbud info, speech recognition, text-to-speech, audio routing and
 * earbud tap buttons) through the "Android" JavaScript bridge.
 */
public class MainActivity extends Activity {
    private static final int PERMS = 7;

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private AudioManager am;

    // Bluetooth
    private BluetoothAdapter adapter;
    private BluetoothProfile a2dp, headset;
    private final Map<String, Integer> batteryFromBroadcast = new HashMap<>();
    private BroadcastReceiver receiver;

    // speech recognition
    private SpeechRecognizer sr;
    private boolean srOnDevice;
    private String srTag = "";
    private long lastLevelAt;
    private CloudAsr cloud;

    // text-to-speech
    private TextToSpeech tts;
    private boolean ttsReady;
    private MediaPlayer player;
    private boolean speakerRouted;
    private int savedCallVolume = -1;

    // earbud mic, ducking, earbud taps
    private boolean earMic;
    private AudioFocusRequest focusRequest;
    private AudioManager.OnAudioFocusChangeListener focusListener = c -> { };
    private MediaSession session;
    private AudioTrack silence;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
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
            }
        });
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");

        tts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { ui.post(() -> speakFinished(id, "done")); }
                @Override public void onError(String id) { ui.post(() -> speakFinished(id, "error")); }
            });
        });

        cloud = new CloudAsr(new CloudAsr.Callback() {
            @Override public void event(String type, String text, String tag) { asrTo(type, text, tag); }
            @Override public void level(float db) {
                long now = System.currentTimeMillis();
                if (now - lastLevelAt < 90) return;
                lastLevelAt = now;
                js("window.onAsrLevel&&window.onAsrLevel(" + db + ")");
            }
        });

        askPermissions();
        setupBluetooth();
    }

    // ---------------------------------------------------------------- permissions

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
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
                        }
                        x.put(p == 0 ? "a2dp" : "hfp", true);
                    }
                }
            }
            o.put("devices", arr);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    private void pushDevices() {
        js("window.onDevices&&window.onDevices(" + devicesJson() + ")");
    }

    private boolean earbudsConnected() {
        if (Build.VERSION.SDK_INT >= 23) {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || (Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_HEADSET)) return true;
            }
        }
        return false;
    }

    private String volumeJson() {
        return "{\"v\":" + am.getStreamVolume(AudioManager.STREAM_MUSIC) + ",\"max\":" + am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) + "}";
    }

    // ---------------------------------------------------------------- speech recognition

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle b) { asr("ready", ""); }
        @Override public void onBeginningOfSpeech() { asr("begin", ""); }
        @Override public void onRmsChanged(float db) {
            long now = System.currentTimeMillis();
            if (now - lastLevelAt < 90) return;
            lastLevelAt = now;
            js("window.onAsrLevel&&window.onAsrLevel(" + db + ")");
        }
        @Override public void onBufferReceived(byte[] b) { }
        @Override public void onEndOfSpeech() { asr("end", ""); }
        @Override public void onError(int error) {
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                try { sr.destroy(); } catch (Exception ignored) { }
                sr = null;
            }
            asr("error", String.valueOf(error));
        }
        @Override public void onResults(Bundle b) { asr("final", first(b)); }
        @Override public void onPartialResults(Bundle b) { asr("partial", first(b)); }
        @Override public void onEvent(int t, Bundle b) { }
    };

    private static String first(Bundle b) {
        if (b == null) return "";
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return r != null && !r.isEmpty() && r.get(0) != null ? r.get(0) : "";
    }

    private void asr(String type, String text) { asrTo(type, text, srTag); }

    private void asrTo(String type, String text, String tag) {
        js("window.onAsr&&window.onAsr(" + JSONObject.quote(type) + "," + JSONObject.quote(text) + "," + JSONObject.quote(tag) + ")");
    }

    private String asrStatus() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) return "ok";
        if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) return "ondevice";
        return "none";
    }

    /** Prefer Google's recognizer: some phones' own default voice assistant service fails for other apps. */
    private ComponentName googleRecognizer() {
        try {
            List<ResolveInfo> l = getPackageManager().queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE), 0);
            ComponentName best = null;
            for (ResolveInfo r : l) {
                if (r.serviceInfo == null) continue;
                String pkg = r.serviceInfo.packageName;
                ComponentName cn = new ComponentName(pkg, r.serviceInfo.name);
                if ("com.google.android.googlequicksearchbox".equals(pkg)) return cn;
                if (best == null && pkg.startsWith("com.google.")) best = cn;
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    private String recognizerName() {
        try {
            List<ResolveInfo> l = getPackageManager().queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE), 0);
            StringBuilder b = new StringBuilder();
            for (ResolveInfo r : l) if (r.serviceInfo != null) b.append(b.length() > 0 ? "," : "").append(r.serviceInfo.packageName);
            return b.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private boolean ensureRecognizer() {
        if (sr != null) return true;
        try {
            ComponentName g = googleRecognizer();
            if (g != null) {
                sr = SpeechRecognizer.createSpeechRecognizer(this, g);
                srOnDevice = false;
            } else if (SpeechRecognizer.isRecognitionAvailable(this)) {
                sr = SpeechRecognizer.createSpeechRecognizer(this);
                srOnDevice = false;
            } else if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
                srOnDevice = true;
            }
        } catch (Exception e) {
            sr = null;
        }
        if (sr != null) sr.setRecognitionListener(listener);
        return sr != null;
    }

    private void startListening(String lang, String tag, String engine) {
        srTag = tag;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askPermissions();
            asr("error", "9");
            return;
        }
        cloud.stop();
        if (!ensureRecognizer()) { asr("error", "none"); return; }
        try { sr.cancel(); } catch (Exception ignored) { }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L);
        try {
            sr.startListening(i);
        } catch (Exception e) {
            try { sr.destroy(); } catch (Exception ignored) { }
            sr = null;
            asr("error", "5");
        }
    }

    private void stopListening() {
        if (cloud != null) cloud.stop();
        if (sr == null) return;
        try { sr.cancel(); } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- text-to-speech and routing

    private AudioAttributes attrsFor(boolean speaker) {
        return new AudioAttributes.Builder()
                .setUsage(speaker ? AudioAttributes.USAGE_VOICE_COMMUNICATION : AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
    }

    /** Sends the next sound to the phone's own loudspeaker even while earbuds are connected. */
    @SuppressWarnings("deprecation")
    private boolean routeToSpeaker() {
        if (!earbudsConnected()) return false;
        am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        boolean ok = false;
        if (Build.VERSION.SDK_INT >= 31) {
            for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) { ok = am.setCommunicationDevice(d); break; }
            }
        } else {
            am.setSpeakerphoneOn(true);
            ok = true;
        }
        if (!ok) { restoreAfterSpeaker(true); return false; }
        speakerRouted = true;
        int max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
        savedCallVolume = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
        try { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, Math.max(savedCallVolume, (int) Math.ceil(max * 0.85)), 0); } catch (Exception ignored) { }
        return true;
    }

    @SuppressWarnings("deprecation")
    private void restoreAfterSpeaker(boolean force) {
        if (!speakerRouted && !force) return;
        speakerRouted = false;
        if (savedCallVolume >= 0) {
            try { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedCallVolume, 0); } catch (Exception ignored) { }
            savedCallVolume = -1;
        }
        if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice();
        else am.setSpeakerphoneOn(false);
        am.setMode(AudioManager.MODE_NORMAL);
        if (earMic) setEarMic(true);
    }

    private String speakingId = null;

    private void speak(String id, String text, String lang, String route, float rate) {
        stopSpeaking(false);
        speakingId = id;
        boolean speaker = "speaker".equals(route) && routeToSpeaker();
        Locale loc = Locale.forLanguageTag(lang);
        boolean local = false;
        if (ttsReady) {
            try {
                int a = tts.isLanguageAvailable(loc);
                local = a >= TextToSpeech.LANG_AVAILABLE;
            } catch (Exception ignored) { }
        }
        if (local) {
            tts.setLanguage(loc);
            tts.setSpeechRate(rate);
            tts.setAudioAttributes(attrsFor(speaker));
            Bundle p = new Bundle();
            p.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f);
            int r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, p, id);
            if (r == TextToSpeech.SUCCESS) return;
        }
        speakOnline(id, text, lang, speaker);
    }

    /** The phone has no offline voice for this language: play Google's online voice instead. */
    private void speakOnline(String id, String text, String lang, boolean speaker) {
        List<String> parts = new ArrayList<>();
        String rest = text.trim();
        while (rest.length() > 180) {
            int cut = -1;
            for (String sep : new String[]{"。", "！", "？", ". ", "! ", "? ", "，", ", ", " "}) {
                int k = rest.lastIndexOf(sep, 180);
                if (k > 40) { cut = k + sep.length(); break; }
            }
            if (cut < 0) cut = 180;
            parts.add(rest.substring(0, cut));
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) parts.add(rest);
        String tl = lang.toLowerCase(Locale.ROOT).startsWith("zh-tw") ? "zh-TW"
                : lang.toLowerCase(Locale.ROOT).startsWith("zh-hk") ? "yue"
                : lang.split("-")[0];
        playChunk(id, parts, 0, tl, speaker);
    }

    private void playChunk(String id, List<String> parts, int idx, String tl, boolean speaker) {
        if (!id.equals(speakingId)) return;
        if (idx >= parts.size()) { speakFinished(id, "done"); return; }
        try {
            String url = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=" + URLEncoder.encode(tl, "UTF-8")
                    + "&q=" + URLEncoder.encode(parts.get(idx), "UTF-8");
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setAudioAttributes(attrsFor(speaker));
            Map<String, String> h = new HashMap<>();
            h.put("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome Mobile Safari/537.36");
            h.put("Referer", "https://translate.google.com/");
            mp.setDataSource(this, Uri.parse(url), h);
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.setOnCompletionListener(m -> { m.release(); if (player == m) player = null; playChunk(id, parts, idx + 1, tl, speaker); });
            mp.setOnErrorListener((m, w, e) -> { m.release(); if (player == m) player = null; speakFinished(id, "error"); return true; });
            mp.prepareAsync();
        } catch (Exception e) {
            speakFinished(id, "error");
        }
    }

    private void speakFinished(String id, String how) {
        if (id == null || !id.equals(speakingId)) return;
        speakingId = null;
        restoreAfterSpeaker(false);
        js("window.onSpeak&&window.onSpeak(" + JSONObject.quote(how) + "," + JSONObject.quote(id) + ")");
    }

    private void stopSpeaking(boolean notify) {
        String id = speakingId;
        speakingId = null;
        try { if (ttsReady) tts.stop(); } catch (Exception ignored) { }
        if (player != null) {
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
        restoreAfterSpeaker(false);
        if (notify && id != null) js("window.onSpeak&&window.onSpeak(\"stopped\"," + JSONObject.quote(id) + ")");
    }

    /** Use the earbuds' own microphone (Bluetooth call mode) instead of the phone's. */
    @SuppressWarnings("deprecation")
    private boolean setEarMic(boolean on) {
        earMic = on;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (on) {
                    am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                    for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                        int t = d.getType();
                        if (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                            if (am.setCommunicationDevice(d)) return true;
                        }
                    }
                    am.setMode(AudioManager.MODE_NORMAL);
                    earMic = false;
                    return false;
                } else {
                    am.clearCommunicationDevice();
                    am.setMode(AudioManager.MODE_NORMAL);
                    return true;
                }
            } else {
                if (on) {
                    if (!am.isBluetoothScoAvailableOffCall()) { earMic = false; return false; }
                    am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                    am.startBluetoothSco();
                    am.setBluetoothScoOn(true);
                } else {
                    am.setBluetoothScoOn(false);
                    am.stopBluetoothSco();
                    am.setMode(AudioManager.MODE_NORMAL);
                }
                return true;
            }
        } catch (Exception e) {
            earMic = false;
            return false;
        }
    }

    // ---------------------------------------------------------------- session: ducking, earbud taps, screen on

    @SuppressWarnings("deprecation")
    private void setSession(boolean on, boolean duck) {
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (duck) {
                if (Build.VERSION.SDK_INT >= 26) {
                    focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                            .setAudioAttributes(attrsFor(false))
                            .setOnAudioFocusChangeListener(focusListener)
                            .build();
                    am.requestAudioFocus(focusRequest);
                } else {
                    am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
                }
            }
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (Build.VERSION.SDK_INT >= 26) {
                if (focusRequest != null) am.abandonAudioFocusRequest(focusRequest);
                focusRequest = null;
            } else {
                am.abandonAudioFocus(focusListener);
            }
            stopEarbudButtons();
        }
    }

    /**
     * Earbud taps arrive as play/pause media buttons. Android sends them to the app that
     * played sound most recently, so while translating we keep a silent track playing.
     */
    @SuppressWarnings("deprecation")
    private void startEarbudButtons() {
        if (session == null) {
            session = new MediaSession(this, "earbuds-translate");
            session.setCallback(new MediaSession.Callback() {
                @Override
                public boolean onMediaButtonEvent(Intent intent) {
                    KeyEvent ke = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    if (ke == null || ke.getAction() != KeyEvent.ACTION_DOWN || ke.getRepeatCount() > 0) return true;
                    String k;
                    switch (ke.getKeyCode()) {
                        case KeyEvent.KEYCODE_MEDIA_NEXT: k = "next"; break;
                        case KeyEvent.KEYCODE_MEDIA_PREVIOUS: k = "prev"; break;
                        case KeyEvent.KEYCODE_HEADSETHOOK:
                        case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                        case KeyEvent.KEYCODE_MEDIA_PLAY:
                        case KeyEvent.KEYCODE_MEDIA_PAUSE: k = "tap"; break;
                        default: return false;
                    }
                    js("window.onEarTap&&window.onEarTap(" + JSONObject.quote(k) + ")");
                    return true;
                }
            });
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        }
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build());
        session.setActive(true);
        if (silence == null) {
            try {
                int rate = 8000;
                silence = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(rate)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(rate * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build();
                silence.write(new short[rate], 0, rate);
                silence.setLoopPoints(0, rate, -1);
                silence.play();
            } catch (Exception e) {
                if (silence != null) { try { silence.release(); } catch (Exception ignored) { } }
                silence = null;
            }
        }
    }

    private void stopEarbudButtons() {
        if (silence != null) {
            try { silence.stop(); } catch (Exception ignored) { }
            try { silence.release(); } catch (Exception ignored) { }
            silence = null;
        }
        if (session != null) {
            session.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0, 1f).build());
            session.setActive(false);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void js(String code) {
        ui.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    @Override
    protected void onStart() {
        super.onStart();
        pushDevices();
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
        stopListening();
        if (sr != null) { try { sr.destroy(); } catch (Exception ignored) { } sr = null; }
        stopSpeaking(false);
        if (tts != null) tts.shutdown();
        setSession(false, false);
        if (session != null) { session.release(); session = null; }
        if (earMic) setEarMic(false);
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
        @JavascriptInterface public String asrStatus() { return MainActivity.this.asrStatus(); }
        @JavascriptInterface public boolean ttsHas(String lang) {
            try { return ttsReady && tts.isLanguageAvailable(Locale.forLanguageTag(lang)) >= TextToSpeech.LANG_AVAILABLE; }
            catch (Exception e) { return false; }
        }
        @JavascriptInterface public boolean earbuds() { return earbudsConnected(); }

        /** Hands-free conversation: listens non-stop and works out who is talking. */
        @JavascriptInterface public void listen(String langThem, String langMe, String tag) {
            ui.post(() -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    askPermissions();
                    asrTo("error", "9", tag);
                    return;
                }
                if (sr != null) { try { sr.cancel(); } catch (Exception ignored) { } }
                cloud.start(langThem, langMe, tag);
            });
        }
        /** One sentence with the phone's own recognizer (kept for fallback use). */
        @JavascriptInterface public void listenSystem(String lang, String tag) { ui.post(() -> startListening(lang, tag, "system")); }
        @JavascriptInterface public String recognizers() { return recognizerName(); }
        @JavascriptInterface public void stopListen() { ui.post(MainActivity.this::stopListening); }
        /** Stop recording now and return what was heard so far as the final result. */
        @JavascriptInterface public void finishListen() {
            ui.post(() -> {
                if (sr != null) { try { sr.stopListening(); } catch (Exception ignored) { } }
            });
        }

        @JavascriptInterface public void speak(String id, String text, String lang, String route, float rate) {
            ui.post(() -> MainActivity.this.speak(id, text, lang, route, rate));
        }
        @JavascriptInterface public void stopSpeak() { ui.post(() -> stopSpeaking(true)); }

        @JavascriptInterface public void session(boolean on, boolean duck) { ui.post(() -> setSession(on, duck)); }
        @JavascriptInterface public boolean earMic(boolean on) { return setEarMic(on); }

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

        @JavascriptInterface public void openBluetooth() {
            ui.post(() -> {
                try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); } catch (Exception ignored) { }
            });
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
            ui.post(() -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface public void askPermissions() { ui.post(MainActivity.this::askPermissions); }

        /** Native GET used when the page cannot reach a translation service with fetch(). Result comes back via window.__httpCb. */
        @JavascriptInterface
        public void httpGet(final String id, final String url) {
            if (url == null || !url.startsWith("https://")) return;
            new Thread(() -> {
                String body = null;
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(12000);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome Mobile Safari/537.36");
                    if (c.getResponseCode() == 200) {
                        InputStream in = c.getInputStream();
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        in.close();
                        body = out.toString("UTF-8");
                    }
                } catch (Exception ignored) {
                } finally {
                    if (c != null) c.disconnect();
                }
                js("window.__httpCb&&window.__httpCb(" + JSONObject.quote(id) + "," + (body == null ? "null" : JSONObject.quote(body)) + ")");
            }).start();
        }
    }
}
