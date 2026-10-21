package com.liskovsoft.smartyoutubetv2.common.app.models.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.app.Application;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.smartyoutubetv2.common.misc.PhoneUi;
import com.liskovsoft.smartyoutubetv2.common.prefs.BlockedChannelData;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** NEWTUBE(shorts): autoplay never lands on a Short on the phone. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ShortsAutoplayTest {
    @After
    public void tearDown() {
        PhoneUi.setEnabled(false);
        if (GlobalPreferences.context() != null) {
            BlockedChannelData.instance(GlobalPreferences.context()).clear();
        }
    }

    @Test
    public void anOrdinaryNextPickIsKept() {
        MediaItem next = item("long1", false);
        assertSame(next, ShortsAutoplay.pick(next, upNext(item("s1", true), item("long2", false)), "now", null));
    }

    @Test
    public void aShortPickGivesWayToTheFirstOrdinaryVideoOfUpNext() {
        MediaItem next = item("s1", false); // the autoplay target carries no marker of its own...
        MediaItem firstOrdinary = item("long2", false);
        List<MediaGroup> upNext = upNext(item("s1", true), item("s2", true), firstOrdinary, item("long3", false));

        // ...but Up next lists it as a Short.
        assertSame(firstOrdinary, ShortsAutoplay.pick(next, upNext, "now", null));
    }

    @Test
    public void aMarkedShortPickIsSkippedToo() {
        MediaItem firstOrdinary = item("long1", false);
        assertSame(firstOrdinary, ShortsAutoplay.pick(item("s9", true), upNext(firstOrdinary), "now", null));
    }

    @Test
    public void theReplacementIsAPlainVideoAndNotTheOnePlaying() {
        MediaItem playing = item("now", false);
        MediaItem mix = item("mix", false, "RDmix", false);
        MediaItem upcoming = item("soon", false, null, true);
        MediaItem ordinary = item("long1", false);

        assertSame(ordinary, ShortsAutoplay.pick(item("s1", true),
                upNext(playing, mix, upcoming, ordinary), "now", null));
    }

    @Test
    public void whatUpNextHidesIsNeverTheReplacement() {
        MediaItem blocked = item("fromBlocked", false);
        MediaItem ordinary = item("long1", false);

        assertSame(ordinary, ShortsAutoplay.pick(item("s1", true), upNext(blocked, ordinary), "now",
                item -> item == blocked));
    }

    @Test
    public void syncSkipsABlockedChannelsVideoToo() {
        GlobalPreferences.instance(RuntimeEnvironment.getApplication()); // VideoGroup's blocked-channel rule
        BlockedChannelData.instance(RuntimeEnvironment.getApplication()).addChannel("UCblocked", "UCblocked"); // id, name (the fakes use the id as the author)
        MediaItem shortPick = item("s1", true);
        MediaItem blocked = item("fromBlocked", false, null, false, "UCblocked");
        MediaItem ordinary = item("long1", false);

        PhoneUi.setEnabled(true);
        Video phone = new Video();
        phone.videoId = "now";
        phone.sync(metadata(shortPick, upNext(shortPick, blocked, ordinary)));
        assertEquals("long1", phone.nextMediaItem.getVideoId());
    }

    @Test
    public void anUpNextOfOnlyShortsEndsAutoplay() {
        assertNull(ShortsAutoplay.pick(item("s1", true), upNext(item("s2", true), item("s3", true)), "now", null));
        assertNull(ShortsAutoplay.pick(item("s1", true), null, "now", null));
    }

    @Test
    public void syncSkipsAShortPickOnThePhoneOnly() {
        MediaItem shortPick = item("s1", true);
        MediaItem ordinary = item("long1", false);
        MediaItemMetadata metadata = metadata(shortPick, upNext(shortPick, ordinary));

        PhoneUi.setEnabled(false);
        Video tv = new Video();
        tv.videoId = "now";
        tv.sync(metadata);
        assertSame("TV keeps YouTube's pick", shortPick, tv.nextMediaItem);

        PhoneUi.setEnabled(true);
        Video phone = new Video();
        phone.videoId = "now";
        phone.sync(metadata);
        assertEquals("long1", phone.nextMediaItem.getVideoId());
    }

    private static List<MediaGroup> upNext(MediaItem... items) {
        return Collections.singletonList(group(Arrays.asList(items)));
    }

    private static MediaItem item(String videoId, boolean isShorts) {
        return item(videoId, isShorts, null, false);
    }

    private static MediaItem item(String videoId, boolean isShorts, String playlistId, boolean upcoming) {
        return item(videoId, isShorts, playlistId, upcoming, "UC" + videoId);
    }

    private static MediaItem item(String videoId, boolean isShorts, String playlistId, boolean upcoming,
                                  String channelId) {
        return fake(MediaItem.class, (name) -> {
            switch (name) {
                case "getVideoId": return videoId;
                case "getChannelId": return channelId;
                case "getAuthor": return channelId;
                case "isShorts": return isShorts;
                case "getPlaylistId": return playlistId;
                case "isUpcoming": return upcoming;
                case "getTitle": return videoId;
                default: return null;
            }
        });
    }

    private static MediaGroup group(List<MediaItem> items) {
        return fake(MediaGroup.class, (name) -> "getMediaItems".equals(name) ? items : null);
    }

    private static MediaItemMetadata metadata(MediaItem next, List<MediaGroup> suggestions) {
        return fake(MediaItemMetadata.class, (name) -> {
            switch (name) {
                case "getNextVideo": return next;
                case "getSuggestions": return suggestions;
                default: return null;
            }
        });
    }

    private interface Answer {
        Object answer(String method);
    }

    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type, Answer answer) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            Object value = answer.answer(method.getName());
            if (value != null) {
                return value;
            }
            Class<?> returnType = method.getReturnType();
            if (returnType == boolean.class) return false;
            if (returnType == int.class) return 0;
            if (returnType == long.class) return 0L;
            if (returnType == float.class) return 0f;
            if (returnType == double.class) return 0d;
            return null;
        });
    }
}
