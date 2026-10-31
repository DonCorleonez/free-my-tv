package io.freemytv;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Prayer times for the home screen widget and the prayer popups.
 *
 * <p>Source: the Aladhan API, method 20 (Kementerian Agama Republik Indonesia: Fajr 20, Isha 18),
 * for Bintaro, South Tangerang (lat -6.2813, lon 106.7363, Asia/Jakarta). Method 19 is Algeria
 * and must NOT be used: on 31 Oct 2026 it gave Subuh 04:15 / Isya 18:54 for Bintaro, 4-8 minutes
 * off the method-20 times (Subuh 04:07, Isya 18:58).
 *
 * <p>Only the 5 obligatory prayers trigger popups (Subuh, Dzuhur, Ashar, Maghrib, Isya); Imsak
 * and Sunrise are fetched too but are shown only in the home widget's full-day line, never as
 * popups. Times are fetched once a day and cached in a small JSON file in the app's files
 * directory (readable by every process of this UID, unlike multi-process SharedPreferences);
 * when offline, the last cached day is used, so the widget and the alarms never go blank.
 *
 * <p>Per-prayer offset to the user's reference app (see OFFSET_MINUTES below): applied to the
 * raw Aladhan times immediately after fetching, before the day is cached, displayed, or used
 * to schedule alarms. Every {@link Day} returned by {@link #fetch} or {@link #cached} is
 * already offset, so the clock widget, the Picks strip, the T-5 pill, and the at-time popup
 * all use the same offset times and no code path can still see raw Aladhan times.
 *
 * <p>Calibration warning: this offset is calibrated ONLY to the Salahtimes app set to
 * Tangerang Selatan as photographed by the user for 31 Oct 2026. It is NOT a universal
 * Kemenag correction: if the app's offset drifts on other dates, re-check it and update
 * the table below rather than assuming these values hold forever.
 *
 * <p>No new permissions: INTERNET is already in the manifest.
 */
final class PrayerTimes {
    private PrayerTimes() {}

    /** Aladhan method 20 = Kementerian Agama Republik Indonesia. Never 19 (Algeria). */
    static final int METHOD = 20;

    /** Locked location from the user's spec: Bintaro / South Tangerang. */
    static final double DEFAULT_LATITUDE = -6.2813, DEFAULT_LONGITUDE = 106.7363;

    /** The 5 prayers that get popups, in order, with their Aladhan field names. */
    static final String[] NAMES = {"Subuh", "Dzuhur", "Ashar", "Maghrib", "Isya"};
    static final String[] FIELDS = {"Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"};
    /** Shown in the widget only, never as popups. */
    static final String[] EXTRA_NAMES = {"Imsak", "Sunrise"};
    static final String[] EXTRA_FIELDS = {"Imsak", "Sunrise"};

    /**
     * Per-prayer offset in minutes, added to the raw Aladhan method-20 times for Bintaro.
     * Aligned with FIELDS above, in the same order as NAMES.
     *
     * <p>Calibrated 31 Oct 2026 only, from the user's Salahtimes app (Tangerang Selatan)
     * versus the live Aladhan method-20 response for Bintaro that day:
     * <pre>
     *              Aladhan   Salahtimes   offset
     *   Subuh      04:07       04:09        +2
     *   Dzuhur     11:37       11:40        +3
     *   Ashar      14:53       14:55        +2
     *   Maghrib    17:46       17:50        +4
     *   Isya       18:58       19:01        +3
     * </pre>
     * Do NOT replace this with a uniform +2: Maghrib needs +4, and Sunrise (below) needs -3.
     */
    static final int[] OFFSET_MINUTES = {+2, +3, +2, +4, +3};

    /**
     * Offset for the widget-only extras, aligned with EXTRA_FIELDS: Imsak is left at its
     * raw Aladhan value (it is fetched as its own Aladhan field, not derived from Fajr, and
     * the user's app photos do not show Imsak, so there is nothing to calibrate it against),
     * while Sunrise is 3 minutes earlier in Salahtimes than in Aladhan (05:24 vs 05:27).
     */
    static final int[] EXTRA_OFFSET_MINUTES = {0, -3};

    /** Marker written into cached days whose times already carry the offset (see parse). */
    static final String OFFSET_MARKER = "_offsetApplied";

    static final String CACHE_FILE = "prayer-times.json";

    /** One day of prayer times. Minutes are minutes since midnight, Asia/Jakarta. */
    static final class Day {
        final String date;              // yyyy-MM-dd, Asia/Jakarta
        final int[] minutes = new int[NAMES.length];
        final String[] text = new String[NAMES.length];
        final int[] extraMinutes = new int[EXTRA_NAMES.length];
        final String[] extraText = new String[EXTRA_NAMES.length];

        Day(String date) { this.date = date; }

        /** Index of the next prayer still to come today, or -1 when all 5 have passed. */
        int nextPrayer(long nowMs) {
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jakarta"));
            c.setTimeInMillis(nowMs);
            int nowMin = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
            for (int i = 0; i < minutes.length; i++) if (minutes[i] > nowMin) return i;
            return -1;
        }
    }

    static String today() {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jakarta"));
        return String.format(Locale.ROOT, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    static String tomorrow() {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jakarta"));
        c.add(Calendar.DAY_OF_MONTH, 1);
        return String.format(Locale.ROOT, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** Wall-clock time (ms) of a prayer on a given yyyy-MM-dd date, Asia/Jakarta. */
    static long at(String date, int minutes) {
        String[] p = date.split("-");
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jakarta"));
        c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]),
                minutes / 60, minutes % 60, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static double latitude() {
        try {
            double v = Double.parseDouble(Config.LATITUDE.trim());
            if (v >= -90 && v <= 90) return v;
        } catch (RuntimeException e) { /* empty or unparsable: fall through to Bintaro */ }
        return DEFAULT_LATITUDE;
    }

    private static double longitude() {
        try {
            double v = Double.parseDouble(Config.LONGITUDE.trim());
            if (v >= -180 && v <= 180) return v;
        } catch (RuntimeException e) { /* empty or unparsable: fall through to Bintaro */ }
        return DEFAULT_LONGITUDE;
    }

    /** The cached day for a date, or null when nothing is cached for it. */
    static Day cached(Context c, String date) {
        try {
            File f = new File(c.getFilesDir(), CACHE_FILE);
            if (!f.exists()) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject root = new JSONObject(sb.toString());
            JSONObject day = root.optJSONObject(date);
            if (day == null) return null;
            return parse(date, day);
        } catch (Exception e) {
            AppLog.w("prayer cache read failed: " + e.getMessage());
            return null;
        }
    }

    /** Today's cached day, or null. Never touches the network. */
    static Day cachedToday(Context c) { return cached(c, today()); }

    /** Any cached day at all (used as the offline fallback), or null. */
    static Day anyCached(Context c) {
        try {
            File f = new File(c.getFilesDir(), CACHE_FILE);
            if (!f.exists()) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject root = new JSONObject(sb.toString());
            if (root.length() == 0) return null;
            String date = root.keys().next();
            return parse(date, root.optJSONObject(date));
        } catch (Exception e) {
            AppLog.w("prayer cache read failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Today's times: the network first, the cache when offline, null only when neither has
     * anything. Call off the UI thread.
     */
    static Day fetchToday(Context c) {
        Day day = fetch(c, today());
        return day != null ? day : cachedToday(c) != null ? cachedToday(c) : anyCached(c);
    }

    /** One date's times from Aladhan, cached on success; null when the fetch fails. */
    static Day fetch(Context c, String date) {
        try {
            String[] p = date.split("-");
            String url = "https://api.aladhan.com/v1/timings/" + p[2] + "-" + p[1] + "-" + p[0]
                    + "?latitude=" + latitude() + "&longitude=" + longitude() + "&method=" + METHOD
                    + "&timezonestring=Asia/Jakarta";   // times are parsed as Asia/Jakarta below, so ask for them as such
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("User-Agent", "FreeMyTV");
            try {
                if (conn.getResponseCode() != 200) return null;
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
                JSONObject timings = new JSONObject(sb.toString()).getJSONObject("data").getJSONObject("timings");
                // Offset is applied by parse() (raw Aladhan -> offset day) and the OFFSET
                // day — not the raw timings — is what gets cached, so a later cache read
                // must not apply the offset a second time (see parse()).
                Day day = parse(date, timings);
                save(c, date, toJson(day));
                return day;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            AppLog.w("prayer fetch failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * The single conversion point from stored/fetched timings to a {@link Day}: every caller
     * (widget, Picks strip, scheduler, popup) gets its times from here, so every returned
     * Day is already offset and no path can see raw Aladhan times.
     *
     * <p>Days cached by THIS build carry {@link #OFFSET_MARKER} and are returned as-is.
     * Days cached by earlier builds (raw Aladhan, no marker) get the offset applied here,
     * once, on read — so an update install (which preserves the cache file) never shows
     * raw times while waiting for the next network fetch, and the offset is never doubled.
     */
    private static Day parse(String date, JSONObject timings) throws org.json.JSONException {
        Day day = new Day(date);
        for (int i = 0; i < NAMES.length; i++) {
            day.text[i] = clean(timings.optString(FIELDS[i], ""));
            day.minutes[i] = toMinutes(day.text[i]);
        }
        for (int i = 0; i < EXTRA_NAMES.length; i++) {
            day.extraText[i] = clean(timings.optString(EXTRA_FIELDS[i], ""));
            day.extraMinutes[i] = toMinutes(day.extraText[i]);
        }
        if (!timings.optBoolean(OFFSET_MARKER, false)) applyOffset(day);
        return day;
    }

    /** Add the per-prayer offset to a Day in place, minutes and display text together. */
    private static void applyOffset(Day day) {
        for (int i = 0; i < NAMES.length; i++) {
            if (day.minutes[i] >= 0) {
                day.minutes[i] = (day.minutes[i] + OFFSET_MINUTES[i] + 1440) % 1440;
                day.text[i] = format(day.minutes[i]);
            }
        }
        for (int i = 0; i < EXTRA_NAMES.length; i++) {
            if (day.extraMinutes[i] >= 0) {
                day.extraMinutes[i] = (day.extraMinutes[i] + EXTRA_OFFSET_MINUTES[i] + 1440) % 1440;
                day.extraText[i] = format(day.extraMinutes[i]);
            }
        }
    }

    /** Serialise an (already offset) Day back to a timings JSON object for the cache. */
    private static JSONObject toJson(Day day) throws org.json.JSONException {
        JSONObject out = new JSONObject();
        for (int i = 0; i < NAMES.length; i++) out.put(FIELDS[i], day.text[i]);
        for (int i = 0; i < EXTRA_NAMES.length; i++) out.put(EXTRA_FIELDS[i], day.extraText[i]);
        out.put(OFFSET_MARKER, true);
        return out;
    }

    private static String format(int minutes) {
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    /** Aladhan returns "04:07 (WIB)" style values: keep the HH:mm part. */
    private static String clean(String v) {
        v = v.trim();
        int sp = v.indexOf(' ');
        return sp > 0 ? v.substring(0, sp) : v;
    }

    private static int toMinutes(String hhmm) {
        try {
            String[] p = hhmm.split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static void save(Context c, String date, JSONObject timings) {
        try {
            File f = new File(c.getFilesDir(), CACHE_FILE);
            JSONObject root = new JSONObject();
            if (f.exists()) {
                try {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                    }
                    root = new JSONObject(sb.toString());
                } catch (Exception e) { /* corrupt cache: start fresh */ }
            }
            // Keep only today and tomorrow: the file never grows past two days.
            String keepToday = today(), keepTomorrow = tomorrow();
            java.util.Iterator<String> keys = root.keys();
            java.util.List<String> drop = new java.util.ArrayList<>();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!key.equals(keepToday) && !key.equals(keepTomorrow) && !key.equals(date)) drop.add(key);
            }
            for (String key : drop) root.remove(key);
            root.put(date, timings);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            AppLog.w("prayer cache save failed: " + e.getMessage());
        }
    }
}
