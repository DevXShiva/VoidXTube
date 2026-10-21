package com.newtube.mobile.player;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.youtubeapi.innertube.ytcfg.EmbedIdentityPersistence;

/**
 * NEWTUBE(embed-persist): persists WEB_EMBED's embed identity (the page's encryptedHostFlags, the
 * visitor they are bound to, and the fetch time its 6 h TTL counts from) across process restarts.
 *
 * <p>Deliberately dumb, like {@link BotWallPrefsStore}: one string, validated on the way back in by
 * MediaServiceCore's {@code EmbedIdentityPersistence}. It lives in the same preferences file on
 * purpose: VideoInfoService's bot-wall restore thread loads that file at process start, so the
 * first WEB_EMBED ask - on a /player worker thread, never main - reads it from memory. {@link #save}
 * never blocks ({@code apply}).</p>
 */
public final class EmbedIdentityPrefsStore implements EmbedIdentityPersistence.Store {
    private static final String PREFS = "newtube_player_routes";
    private static final String KEY = "embed_identity";

    private final Context mContext;
    @Nullable
    private volatile SharedPreferences mPrefs;

    public EmbedIdentityPrefsStore(@NonNull Context context) {
        mContext = context.getApplicationContext();
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
        return prefs().getString(KEY, null);
    }

    @Override
    public void save(@Nullable String snapshot) {
        if (snapshot == null) {
            prefs().edit().remove(KEY).apply();
        } else {
            prefs().edit().putString(KEY, snapshot).apply();
        }
    }
}
