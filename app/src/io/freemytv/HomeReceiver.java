package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * System notices for the main process: the TV booted, this app was updated, the sleep timer ended, or the installer
 * answered about an update (Updates). Not exported.
 */
public class HomeReceiver extends BroadcastReceiver {
    /** A prayer popup is due: PRAYER_EXTRA_INDEX is the prayer, PRAYER_EXTRA_REMIND the kind. */
    static final String PRAYER_ACTION = "io.freemytv.PRAYER";
    static final String PRAYER_EXTRA_INDEX = "prayer", PRAYER_EXTRA_REMIND = "remind";

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            HomeMode.bootCheck(context);
            Updates.schedule(context);
            PrayerScheduler.reschedule(context);   // a reboot clears alarms: queue them again
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            HomeMode.verify(context);   // app updated: complete Free My TV mode if something is missing
            HomeMode.ensureRemoteService(context, 3_000);
            Updates.schedule(context);
            PrayerScheduler.reschedule(context);   // an update clears alarms too
            later(() -> Updates.cleanUp(context));
        } else if (Updates.ACTION.equals(action)) {
            later(() -> Updates.status(context, intent));
        } else if (SleepTimer.ACTION.equals(action)) {
            long setAt = intent.getLongExtra("setAt", 0);
            later(() -> SleepTimer.fire(context, setAt));   // ADB is network
        } else if (PRAYER_ACTION.equals(action)) {
            int prayer = intent.getIntExtra(PRAYER_EXTRA_INDEX, 0);
            boolean remind = intent.getBooleanExtra(PRAYER_EXTRA_REMIND, false);
            later(() -> {
                // The TV asleep: nothing to show over, and the next day's queue is still built.
                if (context.getSystemService(android.os.PowerManager.class).isInteractive()) {
                    try {
                        PrayerPopupService.show(context, prayer, remind);
                    } catch (RuntimeException e) {
                        AppLog.w("prayer popup failed: " + e.getMessage());
                    }
                }
                PrayerScheduler.reschedule(context);   // queue the next day after each popup
            });
        }
    }

    /** Off the main thread (Updates may wait for a check under way), with the notice held open until it's done. */
    private void later(Runnable work) {
        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                work.run();
            } finally {
                result.finish();
            }
        }).start();
    }
}
