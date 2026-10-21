package com.newtube.mobile.ui.playback;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * VOIDXDEV(background): the battery-optimization exemption that keeps background audio alive with
 * the screen off. A media foreground service is enough on stock Android, but OEM battery managers
 * (realme/ColorOS, Xiaomi, ...) still freeze or kill an "optimized" app a few minutes after the
 * screen goes off. The system dialog asked here is the one switch those managers honour.
 */
public final class BackgroundPlay {
    private static final String PREFS = "voidx_background";
    private static final String KEY_ASKED = "battery_asked";

    /** A player session ended in this process: Home asks once, after the first video. */
    static volatile boolean sPlayed;

    private BackgroundPlay() {
    }

    public static boolean isExempt(Context context) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return power == null || power.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /** The system "Let app always run in background?" dialog, else the battery settings list. */
    @SuppressLint("BatteryLife") // sideloaded media app: background audio is the core feature
    public static void request(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (RuntimeException e) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (RuntimeException ignored) {
                // no battery settings on this build
            }
        }
    }

    /** Once ever, after a video was played: asks only while the app is still optimized. */
    public static void askOnce(Activity activity) {
        if (!sPlayed || isExempt(activity)) {
            return;
        }
        android.content.SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_ASKED, false)) {
            return;
        }
        prefs.edit().putBoolean(KEY_ASKED, true).apply();
        request(activity);
    }
}
