package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertEquals;

import android.app.Application;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/**
 * NEWTUBE(issue #9): the fullscreen controls start where the video starts. A 2.35:1 film on a 20:9
 * phone (2712x1220, the reporter's panel) fills the width, and the old fixed 16:9 strip put the
 * controls - and their scrims, which ended there as hard edges - 272 px into the picture.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ControlsStripTest {
    private static final int FIT = PlayerConstants.RESIZE_MODE_DEFAULT;
    private static final float WIDE_16_9 = 16f / 9f;

    @Test
    public void aSixteenByNineVideoKeepsItsPillarboxStrip() {
        assertEquals(272, MobilePlaybackActivity.controlsStrip(2712, 1220, WIDE_16_9, FIT));
        assertEquals(252, MobilePlaybackActivity.controlsStrip(2424, 1080, WIDE_16_9, FIT));
    }

    @Test
    public void aFilmWiderThanTheScreenGetsNoStrip() {
        assertEquals(0, MobilePlaybackActivity.controlsStrip(2712, 1220, 3840f / 1634f, FIT));
        assertEquals(0, MobilePlaybackActivity.controlsStrip(1920, 1080, 2.35f, FIT));
    }

    @Test
    public void aFilmBetweenSixteenByNineAndTheScreenGetsItsOwnStrip() {
        assertEquals(136, MobilePlaybackActivity.controlsStrip(2712, 1220, 2f, FIT));
    }

    @Test
    public void narrowerVideosAndAnUnknownSizeKeepTheSixteenByNineBox() {
        assertEquals(272, MobilePlaybackActivity.controlsStrip(2712, 1220, 4f / 3f, FIT));
        assertEquals(272, MobilePlaybackActivity.controlsStrip(2712, 1220, 9f / 16f, FIT));
        assertEquals(272, MobilePlaybackActivity.controlsStrip(2712, 1220, 0f, FIT));
        assertEquals(272, MobilePlaybackActivity.controlsStrip(2712, 1220, Float.NaN, FIT));
    }

    @Test
    public void fillAndZoomCoverTheWidth() {
        assertEquals(0, MobilePlaybackActivity.controlsStrip(2712, 1220, WIDE_16_9,
                PlayerConstants.RESIZE_MODE_FIT_BOTH));
        assertEquals(0, MobilePlaybackActivity.controlsStrip(2712, 1220, WIDE_16_9,
                PlayerConstants.RESIZE_MODE_STRETCH));
        assertEquals(0, MobilePlaybackActivity.controlsStrip(2712, 1220, WIDE_16_9,
                PlayerConstants.RESIZE_MODE_FIT_WIDTH));
        assertEquals(136, MobilePlaybackActivity.controlsStrip(2712, 1220, 2f,
                PlayerConstants.RESIZE_MODE_FIT_HEIGHT));
    }
}
