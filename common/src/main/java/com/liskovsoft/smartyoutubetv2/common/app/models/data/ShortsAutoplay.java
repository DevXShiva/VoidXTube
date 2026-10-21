package com.liskovsoft.smartyoutubetv2.common.app.models.data;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.sharedutils.helpers.Helpers;

import java.util.List;
import java.util.function.Predicate;

/**
 * NEWTUBE(shorts): the phone has no Shorts, so autoplay must not land on one. When YouTube's own
 * next pick (the /next autoplay target) is a Short, the next video is the first non-Short of Up next
 * instead - or none, if Up next has no ordinary video to offer. Applied by {@link Video#sync} only
 * when {@code PhoneUi} is on; TV keeps YouTube's pick.
 *
 * <p>The autoplay target carries no Shorts marker of its own; it counts as a Short when it says so
 * or when Up next lists the same video as a Short.
 */
final class ShortsAutoplay {
    private ShortsAutoplay() {
    }

    /**
     * @param hidden what Up next leaves out besides Shorts (a blocked channel's videos - see
     *               {@code VideoGroup.add}): never the replacement
     */
    static MediaItem pick(MediaItem next, List<MediaGroup> upNext, String currentVideoId, Predicate<MediaItem> hidden) {
        if (next == null || !isShort(next, upNext)) {
            return next;
        }

        if (upNext != null) {
            for (MediaGroup group : upNext) {
                List<MediaItem> items = group != null ? group.getMediaItems() : null;
                if (items == null) {
                    continue;
                }
                for (MediaItem item : items) {
                    // An ordinary video: not a Short, not a playlist or mix (that would switch
                    // what is playing into a queue), not upcoming, not the one playing now, and one
                    // Up next actually shows.
                    if (item != null && item.getVideoId() != null && !isShort(item, upNext)
                            && item.getPlaylistId() == null && !item.isUpcoming()
                            && !Helpers.equals(item.getVideoId(), currentVideoId)
                            && (hidden == null || !hidden.test(item))) {
                        return item;
                    }
                }
            }
        }

        return null;
    }

    private static boolean isShort(MediaItem item, List<MediaGroup> upNext) {
        if (item.isShorts()) {
            return true;
        }
        String videoId = item.getVideoId();
        if (videoId == null || upNext == null) {
            return false;
        }
        for (MediaGroup group : upNext) {
            List<MediaItem> items = group != null ? group.getMediaItems() : null;
            if (items == null) {
                continue;
            }
            for (MediaItem other : items) {
                if (other != null && other.isShorts() && videoId.equals(other.getVideoId())) {
                    return true;
                }
            }
        }
        return false;
    }
}
