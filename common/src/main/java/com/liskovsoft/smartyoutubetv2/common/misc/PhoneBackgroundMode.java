package com.liskovsoft.smartyoutubetv2.common.misc;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants;

/**
 * NEWTUBE(background-mode): how the phone reads the stored "Play in background" choice
 * ({@code PlayerData.getBackgroundMode()}; the key half lives in
 * {@code GeneralData.getBackgroundPlaybackShortcut()}).
 *
 * <p>A phone has two behaviours for leaving a playing video: picture-in-picture, or the window
 * goes and the audio keeps playing. "Disabled" (the stored default) has always meant PiP there,
 * the TV-only "Play behind" was never offered, and Back keeps its in-app meaning, so the
 * "by pressing BACK" variants act like their HOME twins. Stored values are never rewritten; they
 * are only read through here, by the phone's list and by the player.</p>
 */
public final class PhoneBackgroundMode {
    private PhoneBackgroundMode() {
    }

    /** True when the choice is "Only audio" (any key variant); every other value means PiP. */
    public static boolean isOnlyAudio(int backgroundMode) {
        return backgroundMode == PlayerConstants.BACKGROUND_MODE_SOUND;
    }
}
