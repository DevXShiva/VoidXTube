package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.media.AudioManager;
import android.view.WindowManager;

import com.liskovsoft.smartyoutubetv2.tv.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.ArrayList;
import java.util.List;

/**
 * NEWTUBE(gestures): the brightness and volume swipes - the slider curve, the media stream's own
 * steps, the window's own brightness (never the phone's setting) only while fullscreen in front.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SwipeLevelsTest {
    private Activity mActivity;
    private AudioManager mAudio;
    private SwipeLevels mLevels;
    private final List<Float> mShown = new ArrayList<>();
    private int mHidden;

    @Before
    public void setUp() {
        mActivity = Robolectric.buildActivity(Activity.class).setup().get();
        mAudio = (AudioManager) mActivity.getSystemService(Context.AUDIO_SERVICE);
        shadowOf(mAudio).setStreamMaxVolume(15);
        mAudio.setStreamVolume(AudioManager.STREAM_MUSIC, 6, 0);
        mLevels = new SwipeLevels(mActivity, mActivity.getWindow(), mActivity.getWindow().getDecorView(),
                new SwipeLevels.Pill() {
                    @Override
                    public void showLevel(int iconRes, float level) {
                        mShown.add(level);
                    }

                    @Override
                    public void hideLevel() {
                        mHidden++;
                    }
                });
    }

    @Test
    public void theCurveIsTheQuickSettingsSlider() {
        assertEquals(0f, SwipeLevels.gammaToLinear(0f), 1e-6f);
        assertEquals(1f, SwipeLevels.gammaToLinear(1f), 1e-3f);
        assertEquals(1f / 12f, SwipeLevels.gammaToLinear(0.5f), 1e-6f); // half the slider = 8% of the light
        for (float p = 0f; p <= 1f; p += 0.05f) {
            assertEquals(p, SwipeLevels.linearToGamma(SwipeLevels.gammaToLinear(p)), 1e-3f);
        }
    }

    @Test
    public void theRangeIsThreeQuartersOfTheVideo() {
        assertEquals(750f, SwipeLevels.rangePxFor(1000f), 1e-3f);
    }

    @Test
    public void volumeMovesInTheStreamsOwnSteps() {
        mLevels.begin(SwipeLevels.VOLUME, 1000f); // 750 px for 15 steps: 50 px a step
        assertEquals(6f / 15f, mShown.get(0), 1e-6f);

        mLevels.move(24f); // under half a step: nothing yet
        assertEquals(6, mAudio.getStreamVolume(AudioManager.STREAM_MUSIC));
        mLevels.move(26f);
        assertEquals(7, mAudio.getStreamVolume(AudioManager.STREAM_MUSIC));
        mLevels.move(2000f);
        assertEquals(15, mAudio.getStreamVolume(AudioManager.STREAM_MUSIC));
        assertEquals(1f, mShown.get(mShown.size() - 1), 1e-6f);
        mLevels.move(-2000f);
        assertEquals(0, mAudio.getStreamVolume(AudioManager.STREAM_MUSIC));
        assertEquals(0f, mShown.get(mShown.size() - 1), 1e-6f);
    }

    @Test
    public void brightnessIsTheWindowsAndOnlyWhileActive() {
        mLevels.setBrightnessActive(true);
        mLevels.begin(SwipeLevels.BRIGHTNESS, 1000f);
        float start = mShown.get(0);
        mLevels.move(75f); // a tenth of the range
        assertEquals(Math.min(1f, start + 0.1f), mShown.get(mShown.size() - 1), 1e-4f);
        float window = mActivity.getWindow().getAttributes().screenBrightness;
        assertEquals(SwipeLevels.gammaToLinear(mShown.get(mShown.size() - 1)), window, 1e-4f);

        mLevels.end();
        mLevels.setBrightnessActive(false); // portrait, PiP, another screen: the phone's own level
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
                mActivity.getWindow().getAttributes().screenBrightness, 0f);

        mLevels.setBrightnessActive(true); // fullscreen again: the swiped level comes back
        assertEquals(window, mActivity.getWindow().getAttributes().screenBrightness, 1e-4f);
    }

    @Test
    public void thePillLingersAfterTheSwipe() {
        mLevels.begin(SwipeLevels.VOLUME, 1000f);
        mLevels.end();
        assertEquals(0, mHidden);
        org.robolectric.shadows.ShadowLooper.idleMainLooper(SwipeLevels.PILL_LINGER_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(1, mHidden);
    }

    @Test
    public void iconsFollowTheLevel() {
        assertEquals(R.drawable.ic_player_volume_off, SwipeLevels.iconFor(SwipeLevels.VOLUME, 0f));
        assertEquals(R.drawable.ic_player_volume_down, SwipeLevels.iconFor(SwipeLevels.VOLUME, 0.3f));
        assertEquals(R.drawable.ic_player_volume_up, SwipeLevels.iconFor(SwipeLevels.VOLUME, 0.8f));
        assertEquals(R.drawable.ic_player_brightness_low, SwipeLevels.iconFor(SwipeLevels.BRIGHTNESS, 0.1f));
        assertEquals(R.drawable.ic_player_brightness_medium, SwipeLevels.iconFor(SwipeLevels.BRIGHTNESS, 0.5f));
        assertEquals(R.drawable.ic_player_brightness_high, SwipeLevels.iconFor(SwipeLevels.BRIGHTNESS, 0.9f));
    }
}
