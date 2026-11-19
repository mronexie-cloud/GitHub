package com.zlc.earbuds;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Talks to OnePlus / OPPO / realme earbuds over the same Bluetooth control channel the
 * HeyMelody app uses: battery of each earbud and the case, noise control (incl. ANC levels),
 * game mode, firmware version and "find my earbuds" sound.
 *
 * Frame: AA, len(payload+7), 00 00, command (2 bytes LE), seq, payload length (2 bytes LE), payload.
 * Protocol knowledge from Gadgetbridge (AGPL, OppoHeadphonesProtocol) and the MIT-licensed
 * "oppo-pods" Noctalia plugin by osp54, including its per-model noise-mode table.
 */
final class OppoBuds {
    interface Listener { void state(String json); }

    static final UUID SPP = UUID.fromString("0000079a-d102-11e1-9b23-00025b00a5a5");

    // Noise-mode layouts: product id -> {bit index -> mode type}.
    // Mode type: 1 off, 2/6 transparency, 5 ANC (generic), 4 max, 8 moderate, 3 mild, 7 smart (adaptive).
    private static final Map<String, int[][]> LAYOUTS = new HashMap<>();
    private static final int[][] SIMPLE = {{0, 5}, {1, 1}, {2, 2}};
    private static final int[][] FULL = {{0, 1}, {1, 5}, {2, 2}, {3, 1}, {4, 4}, {5, 8}, {6, 3}, {7, 7}, {8, 2}};
    private static final String[] GAME_SOUND = {"066414", "066814", "067014", "067414", "067814", "06C410", "06C810", "06D810", "06E810", "06EC10"};
    private static final Map<String, String> NAMES = new HashMap<>();

    static {
        for (String p : new String[]{"050C10", "050C14", "062414", "062814", "064C14", "065014", "069010", "06A010", "06F010"}) LAYOUTS.put(p, SIMPLE);
        for (String p : new String[]{"062014", "063014", "063414", "063C14", "064014", "064414", "065C10", "066010", "066414", "066810",
                "066814", "067014", "067C10", "069C10", "06C410", "06D810"}) LAYOUTS.put(p, FULL);
        LAYOUTS.put("060C14", new int[][]{{0, 1}, {1, 2}, {2, 3}, {3, 4}, {4, 7}});
        LAYOUTS.put("061014", new int[][]{{0, 1}, {1, 2}, {2, 3}, {3, 4}});
        LAYOUTS.put("061410", new int[][]{{0, 1}, {1, 2}, {2, 3}, {3, 4}});
        for (String p : new String[]{"062010", "063810", "064010", "065010"}) LAYOUTS.put(p, new int[][]{{0, 5}, {1, 1}, {2, 2}, {3, 6}});
        int[][] withVoice = {{0, 1}, {1, 5}, {2, 2}, {3, 1}, {4, 4}, {5, 8}, {6, 3}, {7, 7}, {8, 2}, {9, 6}};
        LAYOUTS.put("063C10", withVoice);
        LAYOUTS.put("067410", withVoice);
        LAYOUTS.put("067414", new int[][]{{0, 1}, {1, 5}, {2, 2}, {3, 1}, {4, 4}, {6, 3}, {7, 7}, {8, 2}});
        LAYOUTS.put("06C810", new int[][]{{0, 1}, {1, 5}, {2, 2}, {3, 1}, {4, 4}, {6, 3}, {7, 7}, {8, 2}});
        LAYOUTS.put("06E810", new int[][]{{0, 1}, {1, 5}, {2, 2}, {3, 1}, {4, 4}, {6, 3}, {7, 7}, {8, 2}});
        int[][] b4 = {{0, 1}, {1, 5}, {2, 2}, {3, 1}, {4, 4}, {5, 8}, {6, 3}, {7, 7}, {8, 2}, {11, 10}};
        for (String p : new String[]{"065414", "068C10", "06BC10", "06C010"}) LAYOUTS.put(p, b4);
        int[][] b4p = {{0, 1}, {1, 5}, {2, 2}, {3, 1}, {4, 4}, {5, 8}, {6, 3}, {7, 7}, {8, 2}, {11, 10}, {12, 11}, {13, 12}, {14, 13}};
        LAYOUTS.put("067814", b4p);
        LAYOUTS.put("06EC10", b4p);

        NAMES.put("066414", "OnePlus Buds Ace 3");
        NAMES.put("064C14", "OnePlus Buds Ace 2");
        NAMES.put("062814", "OnePlus Buds Ace");
        NAMES.put("063C14", "OnePlus Buds 3");
        NAMES.put("064014", "OnePlus Buds Pro 3");
        NAMES.put("062014", "OnePlus Buds Pro 2");
        NAMES.put("065414", "OnePlus Buds 4");
        NAMES.put("067814", "OnePlus Buds Pro 4");
        NAMES.put("064414", "OnePlus Nord Buds 3 Pro");
        NAMES.put("065014", "OnePlus Nord Buds 3");
        NAMES.put("067C10", "OPPO Enco Air4 Pro");
        NAMES.put("069010", "OPPO Enco Air4");
        NAMES.put("068C10", "OPPO Enco Free4");
        NAMES.put("066010", "OPPO Enco Free3");
        NAMES.put("06EC10", "OPPO Enco X4");
    }

    /** The app's one connection to the earbuds, shared by the earbud page and voice control. */
    static volatile OppoBuds shared;

    private volatile Listener listener;
    private volatile BluetoothSocket socket;
    private volatile OutputStream out;
    private volatile String address;
    private volatile boolean connecting;
    private int seq = 1;

    // state
    private volatile String status = "idle"; // idle, connecting, ok, failed
    private volatile String productId;
    private volatile String fw;
    private volatile int left = -1, right = -1, box = -1;
    private volatile boolean chL, chR, chBox;
    private volatile int noiseBit = -1;
    private volatile Boolean game;
    private volatile boolean finding;

    OppoBuds(Listener l) { this.listener = l; }

    void setListener(Listener l) { listener = l; }

    String address() { return address; }

    boolean isConnected() { return socket != null && out != null; }

    /** Connects to the earbuds' control channel (in the background). */
    synchronized void connect(BluetoothDevice dev) {
        if (dev == null) return;
        if (dev.getAddress().equals(address) && (isConnected() || connecting)) return;
        close();
        address = dev.getAddress();
        connecting = true;
        status = "connecting";
        push();
        final String addr = address;
        new Thread(() -> run(dev, addr), "oppo-buds").start();
    }

    synchronized void close() {
        BluetoothSocket s = socket;
        socket = null;
        out = null;
        address = null;
        connecting = false;
        status = "idle";
        productId = null; fw = null; left = right = box = -1; noiseBit = -1; game = null; finding = false;
        if (s != null) { try { s.close(); } catch (Exception ignored) { } }
    }

    @SuppressLint("MissingPermission")
    private void run(BluetoothDevice dev, String addr) {
        BluetoothSocket s = null;
        // 1) by service UUID (SDP), 2) the channels HeyMelody uses (12, then 13)
        Object[] tries = {"uuid", 12, 13};
        for (Object t : tries) {
            if (!addr.equals(address)) return;
            try {
                if ("uuid".equals(t)) s = dev.createRfcommSocketToServiceRecord(SPP);
                else {
                    Method m = dev.getClass().getMethod("createRfcommSocket", int.class);
                    s = (BluetoothSocket) m.invoke(dev, (Integer) t);
                }
                s.connect();
                break;
            } catch (Throwable e) {
                if (s != null) { try { s.close(); } catch (Exception ignored) { } }
                s = null;
            }
        }
        if (!addr.equals(address)) { if (s != null) try { s.close(); } catch (Exception ignored) { } return; }
        connecting = false;
        if (s == null) { status = "failed"; push(); return; }
        try {
            socket = s;
            out = s.getOutputStream();
            status = "ok";
            push();
            refresh();
            InputStream in = s.getInputStream();
            byte[] buf = new byte[4096];
            ByteArrayOutputStream pending = new ByteArrayOutputStream();
            while (addr.equals(address)) {
                int n = in.read(buf);
                if (n < 0) break;
                pending.write(buf, 0, n);
                byte[] data = pending.toByteArray();
                int used = parse(data);
                pending.reset();
                if (used < data.length) pending.write(data, used, data.length - used);
                push();
            }
        } catch (Throwable ignored) {
        }
        if (addr.equals(address)) {
            socket = null;
            out = null;
            status = "failed";
            push();
        }
    }

    /** Parses as many whole frames as possible; returns how many bytes were used. */
    private int parse(byte[] d) {
        int i = 0;
        while (i < d.length) {
            if ((d[i] & 0xff) != 0xAA) { i++; continue; }
            if (i + 9 > d.length) break;
            int cmd = (d[i + 4] & 0xff) | ((d[i + 5] & 0xff) << 8);
            int len = (d[i + 7] & 0xff) | ((d[i + 8] & 0xff) << 8);
            if (i + 9 + len > d.length) break;
            byte[] p = new byte[len];
            System.arraycopy(d, i + 9, p, 0, len);
            handle(cmd, p);
            i += 9 + len;
        }
        return i;
    }

    private void handle(int cmd, byte[] p) {
        switch (cmd) {
            case 0x8103: // product id
                if (p.length >= 4 && p[0] == 0) productId = String.format("%02X%02X%02X", p[3] & 0xff, p[2] & 0xff, p[1] & 0xff);
                break;
            case 0x8105: // firmware
                if (p.length > 2 && p[0] == 0) fw = firmware(p);
                break;
            case 0x8106:
            case 0x0204:
                if (p.length >= 4 && (p[0] == 0 || p[0] == 1)) battery(p);
                if (cmd == 0x0204 && p.length >= 2 && p[0] == 5) game = p[1] != 0;
                if (cmd == 0x0204 && p.length >= 3 && p[0] != 1) noise(p);
                break;
            case 0x810C:
                noise(p);
                break;
            case 0x0503:
                if (p.length >= 3 && p[0] == 1 && (p[1] == 6 || p[1] == 40)) game = p[2] != 0;
                break;
            default:
        }
    }

    private void battery(byte[] p) {
        int count = p[1] & 0xff;
        for (int k = 2; k + 1 < p.length && k < 2 + count * 2; k += 2) {
            int id = p[k] & 0xff, v = p[k + 1] & 0xff, lvl = v & 0x7f;
            boolean ch = (v & 0x80) != 0;
            if (id == 1) { left = lvl; chL = ch; }
            else if (id == 2) { right = lvl; chR = ch; }
            else if (id == 3) { if (lvl > 0 || ch) { box = lvl; chBox = ch; } }
        }
    }

    private void noise(byte[] p) {
        for (int k = 0; k + 2 < p.length; k++) {
            if (p[k] == 1 && p[k + 1] == 1) {
                int mask = (p[k + 2] & 0xff) | (k + 3 < p.length ? (p[k + 3] & 0xff) << 8 : 0);
                if (mask != 0) noiseBit = Integer.numberOfTrailingZeros(mask);
                return;
            }
        }
    }

    private static String firmware(byte[] p) {
        int end = p.length;
        while (end > 2 && p[end - 1] == 0) end--;
        String s = new String(p, 2, end - 2).trim();
        String[] parts = s.split(",");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i + 2 < parts.length; i += 3) {
            if (!"2".equals(parts[i + 1])) continue;
            if (b.length() > 0) b.append('.');
            b.append(parts[i + 2]);
            if (parts[i + 2].contains(".")) break;
        }
        return b.length() > 0 ? b.toString() : s;
    }

    // ---------------------------------------------------------------- commands

    private synchronized boolean send(int cmd, byte[] payload) {
        OutputStream o = out;
        if (o == null) return false;
        byte[] f = new byte[9 + payload.length];
        f[0] = (byte) 0xAA;
        f[1] = (byte) (payload.length + 7);
        f[4] = (byte) (cmd & 0xff);
        f[5] = (byte) (cmd >> 8);
        f[6] = (byte) (seq++ & 0xff);
        f[7] = (byte) (payload.length & 0xff);
        f[8] = (byte) (payload.length >> 8);
        System.arraycopy(payload, 0, f, 9, payload.length);
        try { o.write(f); o.flush(); return true; } catch (Exception e) { return false; }
    }

    void refresh() {
        send(0x0100, new byte[0]);
        send(0x0103, new byte[0]);
        send(0x0105, new byte[0]);
        send(0x0106, new byte[0]);
        send(0x010C, new byte[]{1, 1});
    }

    /** mode: off, anc, transparency, or an ANC level: smart, mild, moderate, max. */
    boolean setNoise(String mode) {
        int[] want;
        switch (mode) {
            case "off": want = new int[]{1}; break;
            case "transparency": want = new int[]{2, 6}; break;
            case "smart": want = new int[]{7}; break;
            case "mild": want = new int[]{3}; break;
            case "moderate": want = new int[]{8}; break;
            case "max": want = new int[]{4}; break;
            default: want = new int[]{5, 4, 7, 8, 3};
        }
        int[][] lay = layout();
        int bit = -1;
        outer:
        for (int t : want) for (int[] e : lay) if (e[1] == t) { bit = e[0]; break outer; }
        if (bit < 0) return false;
        int mask = 1 << bit;
        byte[] payload = bit < 8 ? new byte[]{1, 1, (byte) mask} : new byte[]{1, 1, (byte) (mask & 0xff), (byte) (mask >> 8)};
        boolean ok = send(0x0404, payload);
        if (ok) { noiseBit = bit; push(); }
        return ok;
    }

    boolean setGame(boolean on) {
        boolean ok = send(0x0403, new byte[]{(byte) (isGameSound() ? 40 : 6), (byte) (on ? 1 : 0)});
        if (ok) { game = on; push(); }
        return ok;
    }

    boolean find(boolean on) {
        boolean ok = send(0x0400, new byte[]{(byte) (on ? 1 : 0)});
        if (ok) { finding = on; push(); }
        return ok;
    }

    private boolean isGameSound() {
        for (String g : GAME_SOUND) if (g.equals(productId)) return true;
        return false;
    }

    private int[][] layout() {
        int[][] l = productId == null ? null : LAYOUTS.get(productId);
        return l != null ? l : FULL;
    }

    private static String typeName(int t) {
        switch (t) {
            case 1: return "off";
            case 2: case 6: return "transparency";
            default: return "anc";
        }
    }

    private static String levelName(int t) {
        switch (t) {
            case 7: return "smart";
            case 3: return "mild";
            case 8: return "moderate";
            case 4: return "max";
            default: return null;
        }
    }

    String json() {
        JSONObject o = new JSONObject();
        try {
            o.put("status", status);
            o.put("addr", address == null ? JSONObject.NULL : address);
            o.put("pid", productId == null ? JSONObject.NULL : productId);
            o.put("model", productId != null && NAMES.containsKey(productId) ? NAMES.get(productId) : JSONObject.NULL);
            o.put("fw", fw == null ? JSONObject.NULL : fw);
            o.put("left", left); o.put("right", right); o.put("case", box);
            o.put("chL", chL); o.put("chR", chR); o.put("chCase", chBox);
            String mode = null, level = null;
            int[][] lay = layout();
            for (int[] e : lay) if (e[0] == noiseBit) { mode = typeName(e[1]); level = levelName(e[1]); }
            o.put("noise", mode == null ? JSONObject.NULL : mode);
            o.put("level", level == null ? JSONObject.NULL : level);
            JSONArray levels = new JSONArray();
            for (String lv : new String[]{"smart", "mild", "moderate", "max"}) {
                int t = "smart".equals(lv) ? 7 : "mild".equals(lv) ? 3 : "moderate".equals(lv) ? 8 : 4;
                for (int[] e : lay) if (e[1] == t) { levels.put(lv); break; }
            }
            o.put("levels", levels);
            o.put("game", game == null ? JSONObject.NULL : game);
            o.put("finding", finding);
        } catch (Exception ignored) { }
        return o.toString();
    }

    private void push() { listener.state(json()); }
}
