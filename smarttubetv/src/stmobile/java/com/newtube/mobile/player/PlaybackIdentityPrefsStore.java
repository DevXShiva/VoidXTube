package com.newtube.mobile.player;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.youtubeapi.app.PlaybackIdentityBook;
import com.liskovsoft.youtubeapi.videoinfo.V2.sources.PlaybackWallMemory;

/**
 * NEWTUBE(playback-identity): persists the playback identity re-roll budget and the kept identity
 * (PlaybackIdentityBook) - or, under {@link #KEY_WALLS}, the walls (PlaybackWallMemory: visitor
 * fingerprints, never the visitor) - across process restarts, so a cold start under the wall
 * neither re-rolls again inside the 6 h window nor asks a walled source first. One string per key,
 * validated by MediaServiceCore; the same preferences file as the other player route records
 * ({@link EmbedIdentityPrefsStore}). {@link #save} never blocks ({@code apply}).
 */
public final class PlaybackIdentityPrefsStore implements PlaybackIdentityBook.Store,
        PlaybackWallMemory.Store {
    private static final String PREFS = "newtube_player_routes";
    private static final String KEY_IDENTITY = "playback_identity";
    /** PlaybackWallMemory's snapshot. */
    public static final String KEY_WALLS = "playback_walls";

    private final Context mContext;
    private final String mKey;
    @Nullable
    private volatile SharedPreferences mPrefs;

    public PlaybackIdentityPrefsStore(@NonNull Context context) {
        this(context, KEY_IDENTITY);
    }

    public PlaybackIdentityPrefsStore(@NonNull Context context, @NonNull String key) {
        mContext = context.getApplicationContext();
        mKey = key;
    }

    private SharedPreferences prefs() {
        SharedPreferences prefs = mPrefs;
        if (prefs == null) {
            prefs = mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            mPrefs = prefs;
        }
        return prefs;
    }

    @Nullable
    @Override
    public String load() {
        return prefs().getString(mKey, null);
    }

    @Override
    public void save(@Nullable String snapshot) {
        if (snapshot == null) {
            prefs().edit().remove(mKey).apply();
        } else {
            prefs().edit().putString(mKey, snapshot).apply();
        }
    }
}
