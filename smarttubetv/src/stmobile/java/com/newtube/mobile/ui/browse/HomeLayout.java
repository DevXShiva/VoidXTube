package com.newtube.mobile.ui.browse;

import android.content.Context;

/** VOIDXDEV(home-rows): Settings > General > Home layout - one feed (default) or SmartTube's rows. */
public final class HomeLayout {
    private static final String PREFS = "voidx_ui";
    private static final String KEY_ROWS = "home_rows";

    private HomeLayout() {
    }

    public static boolean isRows(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ROWS, false);
    }

    public static void setRows(Context context, boolean rows) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ROWS, rows).apply();
    }
}
