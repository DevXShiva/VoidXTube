package com.newtube.mobile.ui.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import android.app.Application;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.ArrayList;
import java.util.List;

/** A section repainted within its TTL is the current content: all of it, not its first 120 cards. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class FeedCacheTest {
    private static final int SECTION = 0; // Home

    @After
    public void tearDown() {
        FeedCache.clear();
    }

    @Test
    public void aDeepFeedIsRepaintedWhole() {
        List<Video> grid = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            Video video = new Video();
            video.videoId = "v" + i;
            grid.add(video);
        }

        FeedCache.put(SECTION, grid);
        List<Video> repainted = FeedCache.get(SECTION);

        assertEquals(300, repainted.size());
        assertSame("the same cards, in order", grid.get(299), repainted.get(299));
    }

    @Test
    public void theSnapshotIsACopy() {
        List<Video> grid = new ArrayList<>();
        Video video = new Video();
        video.videoId = "a";
        grid.add(video);

        FeedCache.put(SECTION, grid);
        grid.clear(); // the grid moves on (a refresh clears it)

        assertEquals(1, FeedCache.get(SECTION).size());
    }

    @Test
    public void everyShelfOfTheSnapshotStaysReachable() {
        // Video.group is a WeakReference: the snapshot pins the groups so a repainted grid can still
        // be continued. Two shelves, interleaved as the grid shows them.
        List<Video> shelfA = new ArrayList<>();
        List<Video> shelfB = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Video a = new Video();
            a.videoId = "a" + i;
            shelfA.add(a);
            Video b = new Video();
            b.videoId = "b" + i;
            shelfB.add(b);
        }
        VideoGroup groupA = VideoGroup.from(shelfA);
        VideoGroup groupB = VideoGroup.from(shelfB);
        List<Video> grid = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            grid.add(shelfA.get(i));
            grid.add(shelfB.get(i));
        }

        FeedCache.put(SECTION, grid);

        List<Video> repainted = FeedCache.get(SECTION);
        assertSame(groupA, repainted.get(4).getGroup());
        assertSame(groupB, repainted.get(5).getGroup());
    }
}
