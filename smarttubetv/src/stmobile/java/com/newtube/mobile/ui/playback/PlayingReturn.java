package com.newtube.mobile.ui.playback;

import android.content.Context;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;

/**
 * NEWTUBE(same-video-tap): a card tap on the video that is already playing in the PiP window or
 * the in-app mini player brings that player back, like YouTube, instead of opening the video
 * again. The open path re-fed the same video through onNewVideo, which re-prepared the stream:
 * a second /player walk and media fetch, and the position jumped (reset to 0, then the saved
 * position). Only the same video in the same playlist context (and the same local/streamed copy)
 * counts; anything else opens as before. Installed from MobileMainApplication as
 * {@code VideoActionPresenter}'s phone hook, so only card taps (feeds, search, channels) route
 * here - "Play from start" and the other menu opens still reopen on purpose.
 */
public final class PlayingReturn {
    enum Route {
        /** Not the playing video, or no PiP/mini player: the ordinary open. */
        OPEN,
        /** Expand the pinned PiP window back to the full player. */
        EXPAND_PIP,
        /** Expand the in-app mini player back to the full player. */
        EXPAND_MINI
    }

    private PlayingReturn() {
    }

    /** Returns true when the tap was answered by bringing the playing player back. */
    public static boolean bringToFront(Context context, @Nullable Video tapped) {
        MobilePlaybackActivity player = SystemPipBridge.player();
        boolean alive = player != null && !player.isFinishing() && !player.isDestroyed();
        Video playing = alive ? player.getVideo() : null;
        Route route = route(tapped, playing,
                alive && player.isPinnedForRestore(), alive && MiniPlayerBridge.isActive());
        if (route == Route.OPEN) {
            if (tapped != null && playing != null && Helpers.equals(tapped.videoId, playing.videoId)) {
                NetPath.log("same-video-tap video=" + tapped.videoId + " route=open list="
                        + tapped.playlistId + "/" + playing.playlistId);
            }
            return false;
        }

        // The card armed its thumbnail morph for a new open; this is an expand, not an open.
        PlayerTransitionBridge.clear();
        NetPath.log("same-video-tap video=" + tapped.videoId
                + " route=" + (route == Route.EXPAND_PIP ? "pip" : "mini")
                + " list=" + tapped.playlistId + "/" + playing.playlistId);
        if (route == Route.EXPAND_PIP) {
            SystemPipBridge.restore(player);
        } else {
            MiniPlayerBridge.expand(context);
        }
        return true;
    }

    static Route route(@Nullable Video tapped, @Nullable Video playing, boolean pinned, boolean miniActive) {
        if (!isSameVideo(tapped, playing)) {
            return Route.OPEN;
        }
        if (pinned) {
            return Route.EXPAND_PIP;
        }
        return miniActive ? Route.EXPAND_MINI : Route.OPEN;
    }

    static boolean isSameVideo(@Nullable Video tapped, @Nullable Video playing) {
        return tapped != null && playing != null && tapped.videoId != null
                && Helpers.equals(tapped.videoId, playing.videoId)
                && Helpers.equals(tapped.playlistId, playing.playlistId)
                && tapped.isLocal() == playing.isLocal();
    }
}
