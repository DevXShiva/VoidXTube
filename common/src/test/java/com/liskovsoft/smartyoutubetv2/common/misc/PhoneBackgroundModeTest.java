package com.liskovsoft.smartyoutubetv2.common.misc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants;

import org.junit.Test;

/** NEWTUBE(background-mode): every stored value reads as one of the phone's two choices. */
public class PhoneBackgroundModeTest {

    @Test
    public void onlyAudioIsTheOnlyAudioChoice() {
        assertTrue(PhoneBackgroundMode.isOnlyAudio(PlayerConstants.BACKGROUND_MODE_SOUND));
    }

    @Test
    public void everythingElseMeansPictureInPicture() {
        assertFalse("Disabled, the stored default",
                PhoneBackgroundMode.isOnlyAudio(PlayerConstants.BACKGROUND_MODE_DEFAULT));
        assertFalse(PhoneBackgroundMode.isOnlyAudio(PlayerConstants.BACKGROUND_MODE_PIP));
        assertFalse("TV-only Play behind",
                PhoneBackgroundMode.isOnlyAudio(PlayerConstants.BACKGROUND_MODE_PLAY_BEHIND));
    }
}
