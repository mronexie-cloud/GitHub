package com.zlc.deathclock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * Minimal SMTP client: AUTH PLAIN, one recipient, UTF-8 text + HTML. Gmail is reached on
 * smtp.gmail.com:465 over SSL with the account's 16-character App Password; the socket comes
 * from {@link Opener} so tests can talk to a fake server over plain TCP.
 */
final class Mailer {
    private Mailer() { }

    interface Opener {
        Socket open(String host, int port) throws IOException;
    }

    static final class SmtpException extends IOException {
        final int code;

        SmtpException(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    /** Turns a failure into a sentence the user can act on. */
    static String explain(Exception e) {
        if (e instanceof SmtpException) {
            int c = ((SmtpException) e).code;
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (c == 534 || m.contains("5.7.9")) return "需要“应用专用密码”：请先给 Google 账号开启两步验证，再创建应用专用密码，填到 App 里（不是 Gmail 登录密码）。";
            if (c == 535) return "邮箱或应用专用密码不对。请确认填的是 16 位应用专用密码，邮箱要写完整（xxx@gmail.com）。";
            if (c == 550 || c == 553) return "收件邮箱地址不对，请检查。";
            return "邮件服务器拒绝了（" + c + "）：" + m;
        }
        if (e instanceof java.net.UnknownHostException) return "网络不通，连不上 Gmail。请检查网络后再试。";
        if (e instanceof java.net.SocketTimeoutException) return "连接 Gmail 超时，请稍后再试。";
        String m = e.getMessage();
        return "发送失败：" + (m == null ? e.getClass().getSimpleName() : m);
    }

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /** Standard Base64 (java.util.Base64 needs Android 8; this app supports Android 7). */
    static String b64(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder o = new StringBuilder((b.length + 2) / 3 * 4);
        for (int i = 0; i < b.length; i += 3) {
            int n = (b[i] & 0xff) << 16 | (i + 1 < b.length ? (b[i + 1] & 0xff) << 8 : 0) | (i + 2 < b.length ? b[i + 2] & 0xff : 0);
            o.append(B64[n >>> 18 & 63]).append(B64[n >>> 12 & 63]);
            o.append(i + 1 < b.length ? B64[n >>> 6 & 63] : '=');
            o.append(i + 2 < b.length ? B64[n & 63] : '=');
        }
        return o.toString();
    }

    /** RFC 2047 encoded-word for a UTF-8 header, split so each line stays short. */
    static String encodeHeader(String s) {
        boolean ascii = true;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 126 || s.charAt(i) < 32) { ascii = false; break; }
        if (ascii) return s;
        StringBuilder out = new StringBuilder();
        StringBuilder chunk = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            int n = ch.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + n > 39 && chunk.length() > 0) {
                out.append(out.length() > 0 ? "\r\n " : "").append("=?UTF-8?B?").append(b64(chunk.toString())).append("?=");
                chunk.setLength(0);
                bytes = 0;
            }
            chunk.append(ch);
            bytes += n;
            i += Character.charCount(cp);
        }
        if (chunk.length() > 0) out.append(out.length() > 0 ? "\r\n " : "").append("=?UTF-8?B?").append(b64(chunk.toString())).append("?=");
        return out.toString();
    }

    private static String wrap76(String b64) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < b64.length(); i += 76) s.append(b64, i, Math.min(b64.length(), i + 76)).append("\r\n");
        return s.toString();
    }

    static String message(String from, String to, String subject, String text, String html) {
        String boundary = "=_dc_" + UUID.randomUUID().toString().replace("-", "");
        SimpleDateFormat df = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
        String domain = from.contains("@") ? from.substring(from.indexOf('@') + 1) : "localhost";
        StringBuilder m = new StringBuilder();
        m.append("From: ").append(encodeHeader("死亡倒计时")).append(" <").append(from).append(">\r\n");
        m.append("To: <").append(to).append(">\r\n");
        m.append("Subject: ").append(encodeHeader(subject)).append("\r\n");
        m.append("Date: ").append(df.format(new Date())).append("\r\n");
        m.append("Message-ID: <").append(UUID.randomUUID()).append("@").append(domain).append(">\r\n");
        m.append("MIME-Version: 1.0\r\n");
        m.append("Content-Type: multipart/alternative; boundary=\"").append(boundary).append("\"\r\n\r\n");
        m.append("--").append(boundary).append("\r\n");
        m.append("Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n");
        m.append(wrap76(b64(text)));
        m.append("--").append(boundary).append("\r\n");
        m.append("Content-Type: text/html; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n");
        m.append(wrap76(b64(html)));
        m.append("--").append(boundary).append("--\r\n");
        return m.toString();
    }

    private static final class Conn {
        final BufferedReader in;
        final OutputStream out;

        Conn(Socket s) throws IOException {
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = s.getOutputStream();
        }

        /** Reads a (possibly multi-line) reply and checks its code. */
        String expect(int... ok) throws IOException {
            StringBuilder all = new StringBuilder();
            String line;
            int code;
            while (true) {
                line = in.readLine();
                if (line == null) throw new IOException("邮件服务器断开了连接");
                all.append(line).append('\n');
                if (line.length() < 3) throw new IOException("邮件服务器回复异常：" + line);
                code = Integer.parseInt(line.substring(0, 3));
                if (line.length() == 3 || line.charAt(3) != '-') break;
            }
            for (int c : ok) if (c == code) return all.toString();
            throw new SmtpException(code, all.toString().trim());
        }

        void send(String cmd) throws IOException {
            out.write((cmd + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    static void send(Opener opener, String host, int port, String user, String pass,
                     String to, String subject, String text, String html) throws IOException {
        try (Socket s = opener.open(host, port)) {
            s.setSoTimeout(30_000);
            Conn c = new Conn(s);
            c.expect(220);
            c.send("EHLO deathclock.local");
            c.expect(250);
            c.send("AUTH PLAIN " + b64("\0" + user + "\0" + pass.replace(" ", "")));
            c.expect(235);
            c.send("MAIL FROM:<" + user + ">");
            c.expect(250);
            c.send("RCPT TO:<" + to + ">");
            c.expect(250, 251);
            c.send("DATA");
            c.expect(354);
            String msg = message(user, to, subject, text, html);
            StringBuilder data = new StringBuilder();
            for (String line : msg.split("\r\n", -1)) {
                data.append(line.startsWith(".") ? "." + line : line).append("\r\n"); // dot-stuffing
            }
            c.out.write(data.toString().getBytes(StandardCharsets.UTF_8));
            c.send(".");
            c.expect(250);
            c.send("QUIT");
            try { c.expect(221); } catch (IOException ignored) { }
        }
    }
}
