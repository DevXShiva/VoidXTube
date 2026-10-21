package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.newtube.mobile.ui.playback.PlayingReturn.Route;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/**
 * NEWTUBE(same-video-tap): tapping the card of the video already playing in PiP (or the mini
 * player) re-opened it - `position-discontinuity reason=remove`, a full re-prepare, a second
 * /player walk. Now it expands that player; a different video, or the same one in another
 * playlist context, still opens.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PlayingReturnTest {

    private static Video video(String videoId, String playlistId) {
        Video video = new Video();
        video.videoId = videoId;
        video.playlistId = playlistId;
        return video;
    }

    @Test
    public void theSameCardExpandsThePipWindow() {
        assertEquals(Route.EXPAND_PIP, PlayingReturn.route(video("FyS5dAywkEo", null),
                video("FyS5dAywkEo", null), /* pinned= */ true, /* miniActive= */ false));
    }

    @Test
    public void theSameCardExpandsTheMiniPlayer() {
        assertEquals(Route.EXPAND_MINI, PlayingReturn.route(video("FyS5dAywkEo", null),
                video("FyS5dAywkEo", null), false, true));
    }

    @Test
    public void anotherVideoOpensNormally() {
        assertEquals(Route.OPEN, PlayingReturn.route(video("other", null),
                video("FyS5dAywkEo", null), true, false));
        assertEquals(Route.OPEN, PlayingReturn.route(video("other", null),
                video("FyS5dAywkEo", null), false, true));
    }

    @Test
    public void theSameVideoInAnotherPlaylistContextOpensNormally() {
        assertEquals(Route.OPEN, PlayingReturn.route(video("FyS5dAywkEo", "PL1"),
                video("FyS5dAywkEo", null), true, false));
        assertEquals(Route.OPEN, PlayingReturn.route(video("FyS5dAywkEo", "PL1"),
                video("FyS5dAywkEo", "PL2"), false, true));
        assertEquals(Route.EXPAND_MINI, PlayingReturn.route(video("FyS5dAywkEo", "PL1"),
                video("FyS5dAywkEo", "PL1"), false, true));
    }

    @Test
    public void aFullScreenPlayerIsNotAReturn() {
        // Neither pinned nor docked (e.g. a related card in the player itself): open as before.
        assertEquals(Route.OPEN, PlayingReturn.route(video("FyS5dAywkEo", null),
                video("FyS5dAywkEo", null), false, false));
    }

    @Test
    public void theDownloadedCopyIsNotTheStream() {
        Video local = video("FyS5dAywkEo", null);
        local.localUri = "file:///data/x.mp4";
        assertFalse(PlayingReturn.isSameVideo(local, video("FyS5dAywkEo", null)));
        assertTrue(PlayingReturn.isSameVideo(video("FyS5dAywkEo", null), video("FyS5dAywkEo", null)));
    }

    @Test
    public void nothingPlayingOrNoIdIsNeverTheSame() {
        assertFalse(PlayingReturn.isSameVideo(video("FyS5dAywkEo", null), null));
        assertFalse(PlayingReturn.isSameVideo(video(null, null), video(null, null)));
        assertFalse(PlayingReturn.isSameVideo(null, video("FyS5dAywkEo", null)));
    }
}
