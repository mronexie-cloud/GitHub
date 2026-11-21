package com.zlc.earbuds;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** Text translation (Google's free web endpoints, MyMemory as a last resort). Blocking: call off the main thread. */
final class Translator {
    private Translator() { }

    static String translate(String text, String sl, String tl) throws Exception {
        Exception last = null;
        for (int i = 0; i < 3; i++) {
            try {
                String r = i == 0 ? google(text, sl, tl) : i == 1 ? googleAlt(text, sl, tl) : myMemory(text, sl, tl);
                if (r != null && !r.trim().isEmpty()) return r.trim();
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new Exception("translation failed");
    }

    private static String google(String text, String sl, String tl) throws Exception {
        JSONArray d = new JSONArray(get("https://translate.googleapis.com/translate_a/single?client=gtx&sl=" + enc(sl)
                + "&tl=" + enc(tl) + "&dt=t&q=" + enc(text)));
        JSONArray parts = d.getJSONArray(0);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONArray p = parts.optJSONArray(i);
            if (p != null && !p.isNull(0)) b.append(p.optString(0, ""));
        }
        return b.toString();
    }

    private static String googleAlt(String text, String sl, String tl) throws Exception {
        String body = get("https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=" + enc(sl) + "&tl=" + enc(tl) + "&q=" + enc(text));
        body = body.trim();
        if (body.startsWith("[")) {
            JSONArray d = new JSONArray(body);
            Object f = d.opt(0);
            if (f instanceof JSONArray) return ((JSONArray) f).optString(0, null);
            if (f instanceof String) return (String) f;
        } else {
            JSONArray s = new JSONObject(body).optJSONArray("sentences");
            if (s != null) {
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < s.length(); i++) b.append(s.getJSONObject(i).optString("trans", ""));
                return b.toString();
            }
        }
        return null;
    }

    private static String myMemory(String text, String sl, String tl) throws Exception {
        String a = "yue".equals(sl) ? "zh-HK" : sl, b = "yue".equals(tl) ? "zh-HK" : tl;
        JSONObject d = new JSONObject(get("https://api.mymemory.translated.net/get?mt=1&q=" + enc(text) + "&langpair=" + enc(a + "|" + b)));
        if (!"200".equals(String.valueOf(d.opt("responseStatus")))) return null;
        JSONArray m = d.optJSONArray("matches");
        if (m == null) return null;
        for (int i = 0; i < m.length(); i++) {
            JSONObject x = m.getJSONObject(i);
            if ("MT!".equals(x.optString("created-by")) || x.optString("reference").toLowerCase().contains("machine translation")) {
                String t = x.optString("translation", "");
                if (!t.contains("MYMEMORY WARNING")) return t;
            }
        }
        return null;
    }

    private static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8"); }

    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome Mobile Safari/537.36");
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
