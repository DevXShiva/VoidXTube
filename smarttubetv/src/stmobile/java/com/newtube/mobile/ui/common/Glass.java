package com.newtube.mobile.ui.common;

import android.view.View;

/** VOIDXDEV(glass): wires a {@link GlassView} to the {@link GlassSource} it frosts. */
public final class Glass {
    private static final String PREFS = "voidx_app";
    private static final String KEY_LIQUID = "liquid_glass";

    private Glass() {
    }

    /** The Liquid glass setting: off, every glass is a plain frosted blur (no lens, light or grain). */
    public static boolean isLiquid(android.content.Context context) {
        return context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean(KEY_LIQUID, true);
    }

    public static void setLiquid(android.content.Context context, boolean on) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean(KEY_LIQUID, on).apply();
    }

    public static void blur(View glass, View source) {
        if (glass instanceof GlassView && source instanceof GlassSource) {
            ((GlassView) glass).setSource((GlassSource) source);
        }
    }
}
