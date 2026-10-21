package com.newtube.mobile.ui.common;

import android.content.Context;

import com.liskovsoft.googlecommon.common.locale.LocaleManager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * VOIDXDEV(counts): YouTube writes view, like and subscriber counts the way the request's {@code hl}
 * locale does, so a phone set to English (India) gets "2.5 lakh views" and "1.2 crore". The default
 * here asks in the bare language instead ({@code hl=en}); {@code gl} stays the phone's country, so
 * the feed itself does not change - only the counts read "250K" and "12M". The Indian style (the
 * setting) leaves the request as the phone's locale has it.
 */
public final class CountStyle {
    private static final String PREFS = "voidx_app";
    private static final String KEY_INDIAN = "indian_counts";
    /** Countries whose English locale counts in lakh and crore. */
    private static final Set<String> LAKH_COUNTRIES = new HashSet<>(Arrays.asList("IN", "BD", "PK", "NP"));

    private CountStyle() {
    }

    public static boolean isIndian(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_INDIAN, false);
    }

    public static void setIndian(Context context, boolean indian) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_INDIAN, indian).apply();
    }

    /**
     * Applies the style as every screen opens and resumes - the splash included, whose home-feed
     * request is the first. ponytail: a service cache reset (LocaleManager.unhold) rebuilds the
     * locale from the phone's, and MediaServiceCore is not ours to hook, so requests between a reset
     * and the next resume ask in the phone's style.
     */
    public static void install(android.app.Application app) {
        app.registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(android.app.Activity activity, android.os.Bundle state) {
                apply(activity);
            }

            @Override
            public void onActivityResumed(android.app.Activity activity) {
                apply(activity);
            }

            @Override
            public void onActivityStarted(android.app.Activity activity) {
            }

            @Override
            public void onActivityPaused(android.app.Activity activity) {
            }

            @Override
            public void onActivityStopped(android.app.Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(android.app.Activity activity, android.os.Bundle state) {
            }

            @Override
            public void onActivityDestroyed(android.app.Activity activity) {
            }
        });
    }

    static void apply(Context context) {
        if (isIndian(context)) {
            return;
        }
        LocaleManager locale = LocaleManager.instance();
        String language = locale.getLanguage();
        int dash = language != null ? language.indexOf('-') : -1;
        if (dash > 0 && LAKH_COUNTRIES.contains(locale.getCountry())) {
            locale.setLanguage(language.substring(0, dash));
        }
    }
}
