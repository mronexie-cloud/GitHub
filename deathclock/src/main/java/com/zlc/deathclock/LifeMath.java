package com.zlc.deathclock;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Countdown maths. The user enters their age today and the age they want to count down to;
 * the moment they saved that (setupAt) anchors everything. The death moment is setupAt plus
 * (deathAge - age) calendar years. Pure Java so it can be unit-tested on a plain JVM; the page
 * (index.html) mirrors the same rules for the live display.
 */
final class LifeMath {
    private LifeMath() { }

    /** Average Gregorian year. */
    static final long YEAR_MS = 31_556_952_000L;
    static final long DAY_MS = 86_400_000L;

    static long deathTime(long setupAt, int age, int deathAge, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(setupAt);
        c.add(Calendar.YEAR, deathAge - age);
        return c.getTimeInMillis();
    }

    /** Age right now, with a fractional part. */
    static double ageAt(long setupAt, int age, long now) {
        return age + (now - setupAt) / (double) YEAR_MS;
    }

    /** Whole years of age right now, counted in calendar years since setup (birthday = setup day). */
    static int wholeAge(long setupAt, int age, long now, TimeZone tz) {
        if (now <= setupAt) return age;
        return age + remaining(setupAt, now, tz).years;
    }

    static final class Parts {
        int years, months, days, hours, minutes, seconds;
        /** True when the chosen age has already been passed; the parts then count time since. */
        boolean passed;
        long totalDays;
    }

    private static Calendar plus(long start, int years, int months, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(start);
        c.add(Calendar.MONTH, years * 12 + months); // clamps 31 Jan + 1 month to the end of February
        return c;
    }

    /** Calendar difference between now and target, as years / months / days / h / m / s. */
    static Parts remaining(long now, long target, TimeZone tz) {
        Parts p = new Parts();
        p.passed = now > target;
        long a = p.passed ? target : now, b = p.passed ? now : target;
        int y = 0;
        while (plus(a, y + 1, 0, tz).getTimeInMillis() <= b) y++;
        int m = 0;
        while (m < 11 && plus(a, y, m + 1, tz).getTimeInMillis() <= b) m++;
        Calendar c = plus(a, y, m, tz);
        int d = 0;
        while (true) {
            Calendar n = (Calendar) c.clone();
            n.add(Calendar.DAY_OF_MONTH, 1);
            if (n.getTimeInMillis() > b) break;
            c = n;
            d++;
        }
        long rest = b - c.getTimeInMillis();
        p.years = y;
        p.months = m;
        p.days = d;
        p.hours = (int) (rest / 3_600_000L);
        p.minutes = (int) (rest / 60_000L % 60);
        p.seconds = (int) (rest / 1000L % 60);
        p.totalDays = (b - a) / DAY_MS;
        return p;
    }

    /** This year's reminder moment: the chosen month/day at 09:00 local time. */
    static long reminderTime(int year, int month1, int day, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.clear();
        c.set(Calendar.YEAR, year);
        c.set(Calendar.MONTH, month1 - 1);
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.DAY_OF_MONTH, Math.min(day, c.getActualMaximum(Calendar.DAY_OF_MONTH))); // 29 Feb in other years -> 28 Feb
        c.set(Calendar.HOUR_OF_DAY, 9);
        return c.getTimeInMillis();
    }

    static int yearOf(long t, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(t);
        return c.get(Calendar.YEAR);
    }

    /** Is a yearly email due now, given the last year one was sent? */
    static boolean due(long now, int lastSentYear, int month1, int day, TimeZone tz) {
        int y = yearOf(now, tz);
        return lastSentYear < y && now >= reminderTime(y, month1, day, tz);
    }

    /** When the next yearly email should go out. */
    static long nextReminder(long now, int lastSentYear, int month1, int day, TimeZone tz) {
        int y = yearOf(now, tz);
        if (lastSentYear < y) return Math.max(now, reminderTime(y, month1, day, tz));
        return reminderTime(y + 1, month1, day, tz);
    }

    /** On first setup, don't send immediately if this year's date has already gone by. */
    static int initialLastSentYear(long now, int month1, int day, TimeZone tz) {
        int y = yearOf(now, tz);
        return now >= reminderTime(y, month1, day, tz) ? y : y - 1;
    }
}
