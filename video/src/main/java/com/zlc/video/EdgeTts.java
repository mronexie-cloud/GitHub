package com.zlc.video;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Free neural voices from Microsoft Edge's "Read aloud" service (the same service the
 * Python edge-tts package uses). It is not an official API, so callers must be ready for
 * it to fail and fall back to the phone's own text-to-speech.
 *
 * Pure Java + OkHttp, no Android classes, so it can be tested on a plain JVM.
 */
public final class EdgeTts {
    static final String TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    static final String CHROMIUM_FULL = "143.0.3650.75";
    static final String CHROMIUM_MAJOR = "143";
    static final String WSS = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=" + TOKEN;
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
            + CHROMIUM_MAJOR + ".0.0.0 Safari/537.36 Edg/" + CHROMIUM_MAJOR + ".0.0.0";
    private static volatile long skewSeconds = 0;

    private final OkHttpClient client;

    public EdgeTts(OkHttpClient client) {
        this.client = client;
    }

    /** Thrown when the service refuses the connection, with the server's clock if it sent one. */
    public static final class EdgeException extends IOException {
        final int code;
        final Date serverDate;

        EdgeException(String msg, int code, Date serverDate, Throwable cause) {
            super(msg, cause);
            this.code = code;
            this.serverDate = serverDate;
        }
    }

    /** The anti-abuse token the service expects (v2 format, see {@link EdgeToken}). */
    static String secMsGec() {
        return EdgeToken.generate(System.currentTimeMillis() / 1000.0 + skewSeconds, TOKEN);
    }

    /** "zh-CN-XiaoxiaoNeural" -> "Microsoft Server Speech Text to Speech Voice (zh-CN, XiaoxiaoNeural)". */
    static String longVoiceName(String shortName) {
        String[] p = shortName.split("-");
        if (p.length < 3) return shortName;
        String lang = p[0] + "-" + p[1];
        String rest = shortName.substring(lang.length() + 1);
        return "Microsoft Server Speech Text to Speech Voice (" + lang + ", " + rest + ")";
    }

    static String escapeXml(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&apos;"); break;
                default:
                    // The service rejects control characters (vertical tab etc.).
                    if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') b.append(' ');
                    else b.append(c);
            }
        }
        return b.toString();
    }

    private static String timestamp() {
        SimpleDateFormat f = new SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    private static String randomMuid() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.US, "%02X", x));
        return s.toString();
    }

    /**
     * Speaks {@code text} with an Edge voice and returns 24 kHz mono MP3 bytes.
     *
     * @param rate  e.g. "+0%", "+10%", "-10%"
     * @param pitch e.g. "+0Hz"
     */
    public byte[] synthesize(String text, String voice, String rate, String pitch, int timeoutSec) throws IOException {
        try {
            return once(text, voice, rate, pitch, timeoutSec);
        } catch (EdgeException e) {
            // A 403 usually means our clock is off; the service's Date header tells us by how much.
            if (e.code == 403 && e.serverDate != null) {
                skewSeconds = (e.serverDate.getTime() - System.currentTimeMillis()) / 1000;
                return once(text, voice, rate, pitch, timeoutSec);
            }
            throw e;
        }
    }

    private byte[] once(String text, String voice, String rate, String pitch, int timeoutSec) throws IOException {
        String url = WSS + "&ConnectionId=" + UUID.randomUUID().toString().replace("-", "")
                + "&Sec-MS-GEC=" + secMsGec() + "&Sec-MS-GEC-Version=2-" + CHROMIUM_FULL;
        Request req = new Request.Builder()
                .url(url)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("User-Agent", UA)
                .header("Accept-Encoding", "gzip, deflate, br, zstd")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "muid=" + randomMuid() + ";")
                .build();

        final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<IOException> failure = new AtomicReference<>();
        final String requestId = UUID.randomUUID().toString().replace("-", "");
        final String ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
                + "<voice name='" + longVoiceName(voice) + "'>"
                + "<prosody pitch='" + pitch + "' rate='" + rate + "' volume='+0%'>"
                + escapeXml(text)
                + "</prosody></voice></speak>";

        WebSocket ws = client.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket socket, Response response) {
                socket.send("X-Timestamp:" + timestamp() + "\r\n"
                        + "Content-Type:application/json; charset=utf-8\r\n"
                        + "Path:speech.config\r\n\r\n"
                        + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{"
                        + "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},"
                        + "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n");
                socket.send("X-RequestId:" + requestId + "\r\n"
                        + "Content-Type:application/ssml+xml\r\n"
                        // The trailing Z is deliberate: Edge itself sends it.
                        + "X-Timestamp:" + timestamp() + "Z\r\n"
                        + "Path:ssml\r\n\r\n"
                        + ssml);
            }

            @Override
            public void onMessage(WebSocket socket, String msg) {
                if (msg.contains("Path:turn.end")) {
                    socket.close(1000, null);
                    done.countDown();
                }
            }

            @Override
            public void onMessage(WebSocket socket, ByteString bytes) {
                byte[] b = bytes.toByteArray();
                if (b.length < 2) return;
                int headerLen = ((b[0] & 0xff) << 8) | (b[1] & 0xff);
                if (2 + headerLen > b.length) return;
                String header = new String(b, 2, headerLen, StandardCharsets.UTF_8);
                if (header.contains("Path:audio")) {
                    audio.write(b, 2 + headerLen, b.length - 2 - headerLen);
                }
            }

            @Override
            public void onClosed(WebSocket socket, int code, String reason) {
                done.countDown();
            }

            @Override
            public void onFailure(WebSocket socket, Throwable t, Response response) {
                int code = response != null ? response.code() : -1;
                Date serverDate = null;
                if (response != null) {
                    try {
                        serverDate = response.headers().getDate("Date");
                    } catch (Exception ignored) {
                    }
                }
                failure.set(new EdgeException("Edge voice failed (" + code + "): " + t, code, serverDate, t));
                done.countDown();
            }
        });

        try {
            if (!done.await(timeoutSec, TimeUnit.SECONDS)) {
                ws.cancel();
                throw new IOException("Edge voice timed out");
            }
        } catch (InterruptedException e) {
            ws.cancel();
            throw new IOException("interrupted", e);
        }
        if (failure.get() != null && audio.size() == 0) throw failure.get();
        if (audio.size() == 0) throw new IOException("Edge voice returned no audio");
        return audio.toByteArray();
    }
}
