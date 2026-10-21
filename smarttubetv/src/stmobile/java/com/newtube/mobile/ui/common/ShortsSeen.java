package com.newtube.mobile.ui.common;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * VOIDXDEV(shorts): the shorts played lately, so the Shorts queue never serves one twice (the reel
 * feed repeats itself across pages, refreshes and days). Kept like the owner's YT client keeps
 * them: the last 3000, for 7 days, across restarts.
 */
public final class ShortsSeen {
    private static final String PREFS = "voidx_shorts_seen";
    private static final String KEY = "seen";
    private static final int MAX = 3000;
    private static final long KEEP_MS = 7L * 24 * 60 * 60 * 1000;

    /** Video id to when it played, oldest first. */
    private static final LinkedHashMap<String, Long> sSeen = new LinkedHashMap<>();
    @Nullable
    private static SharedPreferences sPrefs;

    private ShortsSeen() {
    }

    /** Reads the saved list once per process; until then (or without it) the list is this session's. */
    public static synchronized void load(Context context) {
        if (sPrefs != null) {
            return;
        }
        sPrefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long cutoff = System.currentTimeMillis() - KEEP_MS;
        for (String entry : sPrefs.getString(KEY, "").split(",")) {
            int colon = entry.indexOf(':');
            if (colon > 0) {
                try {
                    long at = Long.parseLong(entry.substring(colon + 1));
                    if (at > cutoff && !sSeen.containsKey(entry.substring(0, colon))) {
                        sSeen.put(entry.substring(0, colon), at);
                    }
                } catch (NumberFormatException ignored) {
                    // a damaged entry is just not remembered
                }
            }
        }
    }

    public static synchronized void add(@Nullable String videoId) {
        if (videoId == null) {
            return;
        }
        sSeen.remove(videoId); // re-inserted as the newest
        sSeen.put(videoId, System.currentTimeMillis());
        Iterator<String> oldest = sSeen.keySet().iterator();
        while (sSeen.size() > MAX && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
        if (sPrefs != null) {
            StringBuilder saved = new StringBuilder(sSeen.size() * 26);
            for (Map.Entry<String, Long> entry : sSeen.entrySet()) {
                saved.append(entry.getKey()).append(':').append(entry.getValue()).append(',');
            }
            sPrefs.edit().putString(KEY, saved.toString()).apply();
        }
    }

    public static synchronized boolean contains(@Nullable String videoId) {
        return videoId != null && sSeen.containsKey(videoId);
    }
}
