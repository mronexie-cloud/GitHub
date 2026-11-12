package com.zlc.deathclock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

import org.junit.Test;

public class DeathClockTest {
    static final TimeZone KL = TimeZone.getTimeZone("Asia/Kuala_Lumpur");

    static long at(int y, int m, int d, int h, int min, int s) {
        Calendar c = Calendar.getInstance(KL);
        c.clear();
        c.set(y, m - 1, d, h, min, s);
        return c.getTimeInMillis();
    }

    @Test
    public void countdownFromSetup() {
        long setup = at(2026, 11, 13, 7, 0, 0);
        long death = LifeMath.deathTime(setup, 30, 80, KL);
        assertEquals(at(2076, 11, 13, 7, 0, 0), death);
        LifeMath.Parts p = LifeMath.remaining(at(2026, 11, 13, 7, 0, 0), death, KL);
        assertEquals(50, p.years); assertEquals(0, p.months); assertEquals(0, p.days); assertFalse(p.passed);
        p = LifeMath.remaining(at(2027, 1, 20, 18, 30, 15), death, KL);
        // 2027-01-20 18:30:15 -> 2076-11-13 07:00:00 = 49y 9m 23d 12:29:45
        assertEquals(49, p.years); assertEquals(9, p.months); assertEquals(23, p.days);
        assertEquals(12, p.hours); assertEquals(29, p.minutes); assertEquals(45, p.seconds);
    }

    @Test
    public void monthEndsClamp() {
        // 31 Jan -> 1 Mar: one month (to 28 Feb) plus one day
        LifeMath.Parts p = LifeMath.remaining(at(2027, 1, 31, 0, 0, 0), at(2027, 3, 1, 0, 0, 0), KL);
        assertEquals(0, p.years); assertEquals(1, p.months); assertEquals(1, p.days);
    }

    @Test
    public void ageTicksOnTheAnniversary() {
        long setup = at(2026, 11, 13, 7, 0, 0);
        assertEquals(30, LifeMath.wholeAge(setup, 30, at(2027, 11, 13, 6, 59, 59), KL));
        assertEquals(31, LifeMath.wholeAge(setup, 30, at(2027, 11, 13, 7, 0, 0), KL));
        assertEquals(40, LifeMath.wholeAge(setup, 30, at(2036, 12, 1, 0, 0, 0), KL));
    }

    @Test
    public void passedAgeCountsUp() {
        LifeMath.Parts p = LifeMath.remaining(at(2030, 3, 1, 0, 0, 0), at(2029, 3, 1, 0, 0, 0), KL);
        assertTrue(p.passed); assertEquals(1, p.years);
    }

    @Test
    public void yearlyDueLogic() {
        // reminder on 13 Nov 09:00
        long beforeNine = at(2026, 11, 13, 7, 0, 0);
        assertEquals(2025, LifeMath.initialLastSentYear(beforeNine, 11, 13, KL)); // today 09:00 still to come
        assertFalse(LifeMath.due(beforeNine, 2025, 11, 13, KL));
        assertTrue(LifeMath.due(at(2026, 11, 13, 9, 0, 0), 2025, 11, 13, KL));
        assertFalse(LifeMath.due(at(2026, 12, 31, 23, 0, 0), 2026, 11, 13, KL)); // already sent this year
        assertTrue(LifeMath.due(at(2027, 12, 1, 8, 0, 0), 2026, 11, 13, KL)); // phone was off on the day: catch up
        assertEquals(at(2027, 11, 13, 9, 0, 0), LifeMath.nextReminder(at(2026, 11, 14, 0, 0, 0), 2026, 11, 13, KL));
        // 29 Feb falls back to 28 Feb in common years
        assertEquals(at(2027, 2, 28, 9, 0, 0), LifeMath.reminderTime(2027, 2, 29, KL));
        assertEquals(2026, LifeMath.initialLastSentYear(at(2026, 11, 13, 10, 0, 0), 11, 13, KL));
    }

    @Test
    public void emailContent() {
        long setup = at(2026, 11, 13, 7, 0, 0);
        String[] m = EmailContent.build(at(2027, 11, 13, 9, 0, 0), setup, 30, 80, "多陪陪家人 <3", KL);
        assertTrue(m[0], m[0].contains("距离 80 岁还剩 48 年 11 个月 30 天"));
        assertTrue(m[1].contains("你 31 岁了"));
        assertTrue(m[1].contains("多陪陪家人 <3"));
        assertTrue(m[2].contains("多陪陪家人 &lt;3")); // escaped in HTML
        assertTrue(EmailContent.test(at(2027, 11, 13, 9, 0, 0), setup, 30, 80, "", KL)[0].startsWith("【测试】"));
    }

    @Test
    public void base64AndHeaders() {
        assertEquals("AHVAZ21haWwuY29tAHBhc3M=", Mailer.b64("\0u@gmail.com\0pass"));
        assertEquals("5q275Lqh", Mailer.b64("死亡"));
        assertEquals("plain", Mailer.encodeHeader("plain"));
        String h = Mailer.encodeHeader("⏳ 死亡倒计时：距离 80 岁还剩 48 年 11 个月 30 天");
        assertTrue(h.startsWith("=?UTF-8?B?"));
        for (String line : h.split("\r\n")) assertTrue(line.length() < 78);
    }

    /** Runs the full SMTP conversation against a fake server and checks what it received. */
    @Test
    public void smtpConversation() throws Exception {
        final List<String> got = Collections.synchronizedList(new ArrayList<>());
        try (ServerSocket server = new ServerSocket(0)) {
            Thread t = new Thread(() -> {
                try (Socket s = server.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    OutputStream out = s.getOutputStream();
                    java.util.function.Consumer<String> say = x -> {
                        try { out.write((x + "\r\n").getBytes(StandardCharsets.UTF_8)); out.flush(); } catch (Exception ignored) { }
                    };
                    say.accept("220 smtp.test ESMTP");
                    String line;
                    boolean data = false;
                    while ((line = in.readLine()) != null) {
                        got.add(line);
                        if (data) {
                            if (line.equals(".")) { data = false; say.accept("250 2.0.0 OK queued"); }
                            continue;
                        }
                        if (line.startsWith("EHLO")) { say.accept("250-smtp.test"); say.accept("250-AUTH LOGIN PLAIN"); say.accept("250 SMTPUTF8"); }
                        else if (line.startsWith("AUTH PLAIN")) say.accept(line.endsWith(Mailer.b64("\0me@gmail.com\0abcdefghijklmnop")) ? "235 2.7.0 Accepted" : "535 5.7.8 Username and Password not accepted");
                        else if (line.startsWith("MAIL FROM")) say.accept("250 OK");
                        else if (line.startsWith("RCPT TO")) say.accept("250 OK");
                        else if (line.equals("DATA")) { say.accept("354 Go ahead"); data = true; }
                        else if (line.equals("QUIT")) { say.accept("221 bye"); break; }
                    }
                } catch (Exception ignored) { }
            });
            t.start();
            Mailer.send((h, p) -> new Socket("127.0.0.1", server.getLocalPort()), "x", 0,
                    "me@gmail.com", "abcd efgh ijkl mnop", "me@gmail.com", "⏳ 死亡倒计时测试", "正文", "<b>正文</b>");
            t.join(5000);
        }
        assertTrue(got.contains("MAIL FROM:<me@gmail.com>"));
        assertTrue(got.contains("RCPT TO:<me@gmail.com>"));
        assertTrue(got.stream().anyMatch(l -> l.startsWith("Subject: =?UTF-8?B?")));
        assertTrue(got.contains(Mailer.b64("正文")));
        assertTrue(got.contains("QUIT"));
    }

    @Test
    public void wrongPasswordIsExplained() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread t = new Thread(() -> {
                try (Socket s = server.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    OutputStream out = s.getOutputStream();
                    out.write("220 hi\r\n".getBytes()); out.flush();
                    in.readLine(); out.write("250 ok\r\n".getBytes()); out.flush();
                    in.readLine(); out.write("534-5.7.9 Application-specific password required.\r\n534 5.7.9 Learn more\r\n".getBytes()); out.flush();
                } catch (Exception ignored) { }
            });
            t.start();
            try {
                Mailer.send((h, p) -> new Socket("127.0.0.1", server.getLocalPort()), "x", 0, "me@gmail.com", "pw", "me@gmail.com", "s", "t", "h");
                throw new AssertionError("should fail");
            } catch (Mailer.SmtpException e) {
                assertEquals(534, e.code);
                assertTrue(Mailer.explain(e).contains("应用专用密码"));
            }
            t.join(5000);
        }
    }
}
