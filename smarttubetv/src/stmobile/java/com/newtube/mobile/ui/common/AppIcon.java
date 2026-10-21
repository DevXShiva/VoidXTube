package com.newtube.mobile.ui.common;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.DrawableRes;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * VOIDXDEV(app icon): the launcher icon picked in Settings > Customization. Each icon is an
 * {@code activity-alias} of SplashActivity (stmobile AndroidManifest, com.newtube.mobile.icon.*) and
 * exactly one is enabled. The in-app marks (Home's brand pill, the launch splash, the update sheet)
 * follow the pick too.
 *
 * <p>Disabling an alias makes Android close the task it started, and the system then refuses to
 * reopen the app from the background. So a pick is remembered here and applied either by the
 * Restart the pick offers ({@link #restart}: the app reopens through the new alias first, then the
 * old one is disabled) or once the app leaves the screen ({@link #applyPending}, from
 * onTrimMemory(UI_HIDDEN)).
 */
public final class AppIcon {
    /** Alias names; the first is the default (the 2.9.9 icon). */
    public static final String DEFAULT = "Default";
    public static final String CLASSIC = "Classic";
    public static final String FANTASY = "Fantasy";
    private static final String[] ICONS = {DEFAULT, CLASSIC, FANTASY};
    private static final String PREFIX = "com.newtube.mobile.icon.";
    private static final String PREFS = "voidx_app_icon";
    private static final String KEY_PENDING = "pending";
    /** Long enough for the relaunch to replace the old task's root before its alias goes. */
    private static final long DISABLE_DELAY_MS = 1_000;

    private AppIcon() {
    }

    /** The icon Settings shows: a pick waiting to be applied, else the enabled alias. */
    public static String current(Context context) {
        String pending = prefs(context).getString(KEY_PENDING, null);
        return pending != null ? pending : enabled(context);
    }

    /** The picked icon as a launcher (adaptive) icon. */
    @DrawableRes
    public static int launcherIcon(Context context) {
        return launcherIcon(current(context));
    }

    @DrawableRes
    public static int launcherIcon(String icon) {
        return CLASSIC.equals(icon) ? R.mipmap.app_icon_classic
                : FANTASY.equals(icon) ? R.mipmap.app_icon_fantasy : R.mipmap.app_icon;
    }

    /** The picked icon's mark without its tile, for the brand pill. */
    @DrawableRes
    public static int mark(Context context) {
        String icon = current(context);
        // VOIDXDEV(branding): every icon variant shows the VoidTube mark.
        return R.drawable.ic_voidx_mark_art;
    }

    public static void set(Context context, String icon) {
        if (icon.equals(current(context))) {
            return;
        }
        String pending = icon.equals(enabled(context)) ? null : icon;
        prefs(context).edit().putString(KEY_PENDING, pending).apply();
        if (context instanceof Activity) {
            syncSplash((Activity) context);
        }
        if (pending != null && context instanceof Activity) {
            MobileSnackbar.show(context, context.getString(R.string.mobile_app_icon_pending),
                    context.getString(R.string.mobile_settings_restart), () -> restart((Activity) context));
        }
    }

    /**
     * Reopens the app through the picked alias and then disables the others. The relaunch must be
     * a NEW task rooted at the new alias: a task cleared and reused (CLEAR_TASK) keeps the old
     * alias as its origin, and the disable then closes it ("disabled-package").
     */
    public static void restart(Activity context) {
        String icon = prefs(context).getString(KEY_PENDING, null);
        if (icon == null) {
            return;
        }
        prefs(context).edit().remove(KEY_PENDING).apply();
        PackageManager pm = context.getPackageManager();
        pm.setComponentEnabledSetting(component(context, icon),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        context.startActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component(context, icon))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
        context.finishAndRemoveTask();
        Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> disableOthers(app, icon), DISABLE_DELAY_MS);
    }

    /** Enables the picked alias first, then disables the other, so a launcher entry always exists. */
    public static void applyPending(Context context) {
        String icon = prefs(context).getString(KEY_PENDING, null);
        if (icon == null) {
            return;
        }
        prefs(context).edit().remove(KEY_PENDING).apply();
        context.getPackageManager().setComponentEnabledSetting(component(context, icon),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        disableOthers(context, icon);
    }

    private static void disableOthers(Context context, String icon) {
        PackageManager pm = context.getPackageManager();
        for (String other : ICONS) {
            if (!other.equals(icon)) {
                pm.setComponentEnabledSetting(component(context, other),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            }
        }
    }

    /**
     * The launch splash shows the picked mark. An alias can't carry a theme, but Android 13+
     * remembers a splash theme per app; older versions keep the default splash.
     */
    public static void syncSplash(Activity activity) {
        if (Build.VERSION.SDK_INT >= 33) {
            String icon = current(activity);
            activity.getSplashScreen().setSplashScreenTheme(CLASSIC.equals(icon) ? R.style.Theme_VoidTube_Splash_Classic
                    : FANTASY.equals(icon) ? R.style.Theme_VoidTube_Splash_Fantasy : Resources.ID_NULL);
        }
    }

    private static String enabled(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String icon : ICONS) {
            int state = pm.getComponentEnabledSetting(component(context, icon));
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ? DEFAULT.equals(icon)
                    : state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                return icon;
            }
        }
        return DEFAULT;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static ComponentName component(Context context, String icon) {
        return new ComponentName(context.getPackageName(), PREFIX + icon);
    }
}
