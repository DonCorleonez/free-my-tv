package io.freemytv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * Queues the prayer popups: for each of the 5 obligatory prayers, one alarm 5 minutes before
 * (Popup A, the pill) and one at prayer time (Popup B, the centred card). Wall-clock alarms
 * (RTC_WAKEUP): prayer times are wall-clock times in Asia/Jakarta, unlike SleepTimer's
 * elapsed-realtime timer.
 *
 * <p>After each popup fires, the next day's alarms are queued, so the queue never runs dry.
 * Boot and app update re-queue through HomeReceiver. Only in Free My TV mode: Original mode
 * cancels the queue (HomeMode.applyOriginal / revertHomeNow), so the way back stays clean.
 * Times come from PrayerTimes' cache when offline, so a day without internet still pops.
 */
final class PrayerScheduler {
    private PrayerScheduler() {}

    /** 5 minutes before, in milliseconds. */
    static final long REMIND_BEFORE_MS = 5 * 60_000;

    /**
     * Queues today + tomorrow's popups (tomorrow covers a prayer that already passed today
     * when the queue is built late, and a day boundary crossed while the TV is off).
     * Safe to call often: it replaces the previous queue with the same PendingIntents.
     */
    static void reschedule(Context c) {
        if (!isFree(c)) { cancel(c); return; }
        Context app = c.getApplicationContext();
        String[] dates = {PrayerTimes.today(), PrayerTimes.tomorrow()};
        for (String date : dates) {
            PrayerTimes.Day day = PrayerTimes.cached(app, date);
            if (day == null) {
                // No cache yet (first run, or a fresh install): fetch off the caller's thread
                // is the caller's job (HomeView does it); here, queue nothing rather than block.
                continue;
            }
            for (int i = 0; i < PrayerTimes.NAMES.length; i++) {
                if (day.minutes[i] < 0) continue;
                long at = PrayerTimes.at(date, day.minutes[i]);
                set(app, i, true, at - REMIND_BEFORE_MS);
                set(app, i, false, at);
            }
        }
    }

    /** Queues a single day's popups (used after a fetch brings in a day the cache lacked). */
    static void rescheduleDay(Context c, PrayerTimes.Day day) {
        if (!isFree(c)) { cancel(c); return; }
        Context app = c.getApplicationContext();
        for (int i = 0; i < PrayerTimes.NAMES.length; i++) {
            if (day.minutes[i] < 0) continue;
            long at = PrayerTimes.at(day.date, day.minutes[i]);
            set(app, i, true, at - REMIND_BEFORE_MS);
            set(app, i, false, at);
        }
    }

    /** Cancels every prayer alarm and stops any popup on screen. */
    static void cancel(Context c) {
        Context app = c.getApplicationContext();
        AlarmManager am = app.getSystemService(AlarmManager.class);
        if (am == null) return;
        for (int i = 0; i < PrayerTimes.NAMES.length; i++) {
            am.cancel(pending(app, i, true));
            am.cancel(pending(app, i, false));
        }
        app.stopService(new Intent(app, PrayerPopupService.class));
    }

    private static boolean isFree(Context c) {
        try {
            return HomeMode.FREE.equals(HomeMode.state(c).optString("mode"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void set(Context app, int prayer, boolean remind, long atMs) {
        if (atMs <= System.currentTimeMillis()) return;   // already passed: skip, don't fire late
        AlarmManager am = app.getSystemService(AlarmManager.class);
        if (am == null) return;
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending(app, prayer, remind));
        } catch (SecurityException e) {
            // Exact alarms refused on this TV: fall back to an inexact window, like SleepTimer.
            try {
                am.setWindow(AlarmManager.RTC_WAKEUP, atMs, 60_000, pending(app, prayer, remind));
            } catch (RuntimeException e2) {
                AppLog.w("prayer alarm failed: " + e2.getMessage());
            }
        }
    }

    private static PendingIntent pending(Context app, int prayer, boolean remind) {
        Intent i = new Intent(app, HomeReceiver.class);
        i.setAction(HomeReceiver.PRAYER_ACTION);
        i.putExtra(HomeReceiver.PRAYER_EXTRA_INDEX, prayer);
        i.putExtra(HomeReceiver.PRAYER_EXTRA_REMIND, remind);
        int code = prayer * 2 + (remind ? 0 : 1);
        return PendingIntent.getBroadcast(app, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
