package com.ansub.tvhome;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

// Some MediaTek TVs (seen on a OnePlus Y Series) open Live TV over the home screen right after they
// start, which is a black screen when its input has no signal. This watches the first seconds after
// boot and, if Live TV ends up in front, brings the home screen back once.
//
// It needs two permissions granted over adb, otherwise it does nothing:
//   appops set com.ansub.tvhome GET_USAGE_STATS allow       to see that Live TV came to the front
//   appops set com.ansub.tvhome SYSTEM_ALERT_WINDOW allow   to open Home from the background
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "tvhome";
    private static final String[] LIVE_TV = {"com.mediatek.wwtv.tvcenter"};
    private static final long WATCH_MS = 30000, STEP_MS = 250;
    private static boolean watching;

    // Locked boot comes a few seconds before Live TV is opened, boot completed often after it.
    // Whichever arrives first starts the watch. The broadcast isn't held while watching: boot is
    // sent to one app at a time, and holding it would hold up every app after this one.
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action) && !Intent.ACTION_BOOT_COMPLETED.equals(action)) return;
        synchronized (BootReceiver.class) {
            if (watching) return;
            watching = true;
        }
        final Context app = context.getApplicationContext();
        new Thread(() -> watch(app)).start();
    }

    private static void watch(Context context) {
        UsageStatsManager usage = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usage == null) return;
        long bootedAt = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        long until = SystemClock.elapsedRealtime() + WATCH_MS;
        while (SystemClock.elapsedRealtime() < until) {
            String front = frontApp(usage, bootedAt);
            if (front != null && isLiveTv(front)) {
                Log.i(TAG, "Live TV opened at start, going back to Home");
                try {
                    context.startActivity(new Intent(context, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) {
                    Log.w(TAG, "Couldn't open Home: " + e);
                }
                return;
            }
            SystemClock.sleep(STEP_MS);
        }
    }

    // The app most recently brought to the front since boot, or null if none is known (or no access,
    // or the TV isn't unlocked yet).
    private static String frontApp(UsageStatsManager usage, long since) {
        UsageEvents events;
        try {
            events = usage.queryEvents(since, System.currentTimeMillis() + 1000);
        } catch (Exception e) {
            return null;
        }
        if (events == null) return null;
        UsageEvents.Event e = new UsageEvents.Event();
        String front = null;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) front = e.getPackageName();
        }
        return front;
    }

    private static boolean isLiveTv(String pkg) {
        for (String p : LIVE_TV) if (p.equals(pkg)) return true;
        return false;
    }
}
