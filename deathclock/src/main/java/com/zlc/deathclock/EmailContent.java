package com.zlc.deathclock;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** The yearly reminder email: subject, plain text and HTML. Pure Java. */
final class EmailContent {
    private EmailContent() { }

    // Written for this app; one is picked per year so consecutive emails differ.
    private static final String[] LINES = {
            "时间不会停下来等你，但你可以决定把它花在哪里。",
            "把最重要的那件事，放进今天。",
            "想见的人，去见；想说的话，去说。别留到“以后”。",
            "你不是在等人生开始，你已经在人生的中间了。",
            "少一点担心别人怎么看，多一点做自己想做的事。",
            "身体是唯一一处你必须住到最后的地方，照顾好它。",
            "今年结束时，你希望自己记住哪三件事？现在就开始。",
            "倒计时不是为了害怕，而是为了不再拖延。",
    };

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static String[] build(long now, long setupAt, int age, int deathAge, String note, TimeZone tz) {
        long death = LifeMath.deathTime(setupAt, age, deathAge, tz);
        LifeMath.Parts p = LifeMath.remaining(now, death, tz);
        double ageNow = LifeMath.ageAt(setupAt, age, now);
        int ageYears = LifeMath.wholeAge(setupAt, age, now, tz);
        double pct = Math.min(100, Math.max(0, ageNow / deathAge * 100));
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        String date = String.format(Locale.CHINA, "%d年%d月%d日", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        String left = p.years + " 年 " + p.months + " 个月 " + p.days + " 天";
        String line = LINES[Math.floorMod(c.get(Calendar.YEAR), LINES.length)];
        String n = note == null ? "" : note.trim();

        String subject;
        StringBuilder t = new StringBuilder();
        t.append("今天是 ").append(date).append("，你 ").append(ageYears).append(" 岁了。\n\n");
        if (!p.passed) {
            subject = "⏳ 死亡倒计时：距离 " + deathAge + " 岁还剩 " + left;
            t.append("按你设定的 ").append(deathAge).append(" 岁计算，你还剩：\n")
                    .append(left).append("\n（约 ").append(String.format(Locale.CHINA, "%,d", p.totalDays)).append(" 天，")
                    .append(String.format(Locale.CHINA, "%,d", p.totalDays / 7)).append(" 个周末）\n\n");
        } else {
            subject = "⏳ 死亡倒计时：你已经比设定的 " + deathAge + " 岁多活了 " + left;
            t.append("你已经超过了自己设定的 ").append(deathAge).append(" 岁，多出来 ").append(left)
                    .append("。多出来的每一天都是赚的。\n\n");
        }
        t.append(String.format(Locale.CHINA, "人生进度：%.1f%%\n", pct)).append(bar(pct)).append("\n\n");
        t.append(line).append("\n");
        if (!n.isEmpty()) t.append("\n你写给自己的话：\n").append(n).append("\n");
        t.append("\n——\n这封邮件由「死亡倒计时」App 每年自动发送，可以在 App 设置里修改或关闭。\n");

        StringBuilder h = new StringBuilder();
        h.append("<div style=\"background:#000;color:#eee;padding:28px 22px;font-family:-apple-system,'PingFang SC','Noto Sans SC',sans-serif;line-height:1.6;max-width:560px;margin:auto;border-radius:16px\">");
        h.append("<div style=\"color:#888;font-size:14px\">").append(esc(date)).append(" · 你 ").append(ageYears).append(" 岁</div>");
        if (!p.passed) {
            h.append("<div style=\"font-size:15px;margin-top:14px;color:#aaa\">距离 ").append(deathAge).append(" 岁还剩</div>");
        } else {
            h.append("<div style=\"font-size:15px;margin-top:14px;color:#aaa\">已超过设定的 ").append(deathAge).append(" 岁</div>");
        }
        h.append("<div style=\"font-size:34px;font-weight:800;letter-spacing:1px;margin:4px 0;color:#fff\">")
                .append(p.years).append("<span style=\"font-size:16px;color:#888\"> 年 </span>")
                .append(p.months).append("<span style=\"font-size:16px;color:#888\"> 月 </span>")
                .append(p.days).append("<span style=\"font-size:16px;color:#888\"> 天</span></div>");
        if (!p.passed) {
            h.append("<div style=\"color:#888;font-size:13px\">约 ").append(String.format(Locale.CHINA, "%,d", p.totalDays))
                    .append(" 天 · ").append(String.format(Locale.CHINA, "%,d", p.totalDays / 7)).append(" 个周末</div>");
        }
        h.append("<div style=\"margin:22px 0 6px;font-size:13px;color:#aaa\">人生进度 ")
                .append(String.format(Locale.CHINA, "%.1f%%", pct)).append("</div>");
        h.append("<div style=\"height:10px;background:#222;border-radius:99px;overflow:hidden\"><div style=\"height:10px;width:")
                .append(String.format(Locale.US, "%.1f", pct)).append("%;background:#ff3b30\"></div></div>");
        h.append("<p style=\"font-size:18px;margin:26px 0 0;color:#fff\">").append(esc(line)).append("</p>");
        if (!n.isEmpty()) {
            h.append("<div style=\"margin-top:20px;padding:14px 16px;border-left:3px solid #ff3b30;background:#111;color:#ddd\">")
                    .append("<div style=\"font-size:12px;color:#888\">你写给自己的话</div>")
                    .append(esc(n).replace("\n", "<br>")).append("</div>");
        }
        h.append("<div style=\"margin-top:28px;font-size:12px;color:#666\">这封邮件由「死亡倒计时」App 每年自动发送，可以在 App 设置里修改或关闭。</div></div>");
        return new String[]{subject, t.toString(), h.toString()};
    }

    static String bar(double pct) {
        int full = (int) Math.round(pct / 5);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 20; i++) b.append(i < full ? '█' : '░');
        return b.toString();
    }

    static String[] test(long now, long setupAt, int age, int deathAge, String note, TimeZone tz) {
        String[] m = build(now, setupAt, age, deathAge, note, tz);
        m[0] = "【测试】" + m[0];
        return m;
    }
}
