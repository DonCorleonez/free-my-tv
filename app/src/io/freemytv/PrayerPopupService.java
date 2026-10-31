package io.freemytv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The prayer popups, over whatever is playing. A foreground service in a process of its own
 * (:prayer), like SleepOverlay's :sleep, so it never keeps the home screen in memory under
 * another app. The prayer index and kind (remind / at prayer time) come in its Intent.
 *
 * <p>Popup A (5 minutes before): a small pill at the top centre, Dynamic Island style,
 * non-focusable and untouchable, so it never interrupts what is playing. Popup B (at prayer
 * time): a centred card on a dimmed screen, focusable, that consumes Back and touches for
 * exactly one minute, then removes itself. It never stops playback: it only covers the screen
 * for that minute, and the TV is never left blocked — the minute always ends by itself.
 *
 * <p>The texts on Popup B are quoted, not UI chrome, so they are kept verbatim in Indonesian
 * and are NOT run through Ui.tr (a catalogue translation would garble a quote). Only two
 * verified texts are shipped, alternating by prayer:
 * <ul>
 *   <li>QS An-Nisa' 4:103 (Kemenag translation): "Sungguh, salat itu adalah kewajiban yang
 *       ditentukan waktunya atas orang-orang yang beriman."</li>
 *   <li>HR Bukhari 527: the answer to "which deed is dearest to Allah?" was prayer offered at
 *       its early stated fixed times, rendered here as "Amalan yang paling dicintai Allah
 *       adalah salat pada waktunya."</li>
 * </ul>
 * No other ayat, hadis or quote is shipped; anything else needs verification first.
 */
public class PrayerPopupService extends Service {
    /** Exactly one minute, for both popups, as the user specified. */
    static final long DURATION_MS = 60_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private View view;
    private WindowManager.LayoutParams layout;

    private final Runnable dismiss = new Runnable() {
        @Override public void run() {
            remove();
            stopSelf();
        }
    };

    /**
     * Shows a popup, asking for the overlay permission through the TV's own ADB first when it
     * is missing (Android TV has no screen for it), like SleepOverlay.update does.
     */
    static void show(Context c, int prayer, boolean remind) {
        Intent i = new Intent(c, PrayerPopupService.class)
                .putExtra("prayer", prayer).putExtra("remind", remind);
        if (Settings.canDrawOverlays(c)) {
            c.startForegroundService(i);
        } else {
            c.stopService(i);
            if (Capabilities.overlay(c)) allow(c.getApplicationContext(), prayer, remind);
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean allowing =
            new java.util.concurrent.atomic.AtomicBoolean();

    private static void allow(Context c, int prayer, boolean remind) {
        if (!allowing.compareAndSet(false, true)) return;   // one at a time, however fast alarms come
        new Thread(() -> {
            try (Adb adb = Adb.connect(c, 10_000, null)) {
                adb.shell("appops set " + HomeMode.PKG + " SYSTEM_ALERT_WINDOW allow");
            } catch (java.io.IOException e) {
                AppLog.w("prayer overlay: " + e.getMessage());
                allowing.set(false);
                return;
            }
            boolean works = Settings.canDrawOverlays(c);
            for (int i = 0; i < 5 && !works; i++) {
                android.os.SystemClock.sleep(200);
                works = Settings.canDrawOverlays(c);
            }
            Capabilities.prove(c, Capabilities.OVERLAY, works);
            allowing.set(false);
            if (works) new Handler(Looper.getMainLooper()).post(() -> show(c, prayer, remind));
        }).start();
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("prayer", Ui.tr("Prayer Times"),
                NotificationManager.IMPORTANCE_MIN));
        startForeground(2, new Notification.Builder(this, "prayer").setSmallIcon(R.drawable.icon)
                .setContentTitle(Ui.tr("Prayer Times")).build());
        Ui.init(this);   // its own process: the app's font isn't loaded here yet
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // Original mode is the way back: never pop over the factory home screen.
        if (!HomeMode.FREE.equals(HomeMode.state(this).optString("mode"))) {
            stopSelf();
            return START_NOT_STICKY;
        }
        int prayer = intent.getIntExtra("prayer", 0);
        if (prayer < 0 || prayer >= PrayerTimes.NAMES.length) prayer = 0;
        boolean remind = intent.getBooleanExtra("remind", false);
        remove();
        view = remind ? pill(prayer) : card(prayer);
        WindowManager wm = getSystemService(WindowManager.class);
        wm.addView(view, layout);
        handler.removeCallbacks(dismiss);
        handler.postDelayed(dismiss, DURATION_MS);
        return START_NOT_STICKY;   // a killed popup is not re-shown: its minute has passed
    }

    /** Popup A: a small pill at the top centre, non-focusable, untouchable, Dynamic Island style. */
    private View pill(int prayer) {
        float k = getResources().getDisplayMetrics().widthPixels / 1920f;
        TextView v = new TextView(this);
        v.setTypeface(Ui.regular);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, 26 * k);
        v.setTextColor(Color.WHITE);
        v.setText(Ui.trf("{name} in 5 minutes", "name", PrayerTimes.NAMES[prayer]));
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(40 * k);
        pill.setColor(0xE61C1C1E);
        v.setBackground(pill);
        v.setPadding(Math.round(28 * k), Math.round(14 * k), Math.round(28 * k), Math.round(14 * k));
        layout = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        layout.y = Math.round(48 * k);
        return v;
    }

    /** Popup B: a centred card on a dimmed screen, not dismissable during its minute. */
    private View card(int prayer) {
        float k = getResources().getDisplayMetrics().widthPixels / 1920f;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x99000000);   // the screen stays on, dimmed and greyed
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(24 * k);
        bg.setColor(0xFF1C1C1E);
        card.setBackground(bg);
        int pad = Math.round(48 * k);
        card.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setTypeface(Ui.displaySemibold);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, 52 * k);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setText(PrayerTimes.NAMES[prayer]);
        TextView time = new TextView(this);
        time.setTypeface(Ui.regular);
        time.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30 * k);
        time.setTextColor(0xEBFFFFFF);
        time.setGravity(Gravity.CENTER);
        PrayerTimes.Day day = PrayerTimes.cachedToday(this);
        String at = day != null && day.minutes[prayer] >= 0 ? day.text[prayer] : "";
        time.setText(Ui.trf("Prayer time: {time}", "time", at));
        TextView quote = new TextView(this);
        quote.setTypeface(Ui.regular);
        quote.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30 * k);
        quote.setTextColor(0xEBFFFFFF);
        quote.setGravity(Gravity.CENTER);
        quote.setPadding(0, Math.round(24 * k), 0, 0);
        // Verified texts only, alternating by prayer (see the class comment for the sources).
        if (prayer % 2 == 0) {
            quote.setText("\u201CSungguh, salat itu adalah kewajiban yang ditentukan waktunya "
                    + "atas orang-orang yang beriman.\u201D\n\u2014 QS An-Nisa' 4:103");
        } else {
            quote.setText("\u201CAmalan yang paling dicintai Allah adalah salat pada waktunya.\u201D"
                    + "\n\u2014 HR Bukhari 527");
        }
        card.addView(title);
        card.addView(time);
        card.addView(quote);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(card, lp);
        // Not dismissable during the minute: Back and every touch are consumed, never passed on.
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        root.setOnKeyListener((v, keyCode, e) -> keyCode == KeyEvent.KEYCODE_BACK);
        root.setOnTouchListener((v, e) -> true);
        layout = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                0, PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.CENTER;
        return root;
    }

    private void remove() {
        handler.removeCallbacks(dismiss);
        if (view != null) {
            try {
                getSystemService(WindowManager.class).removeView(view);
            } catch (RuntimeException e) { /* already gone */ }
            view = null;
        }
    }

    @Override public void onDestroy() {
        remove();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
