package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.pm.PackageManager;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCategory;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.misc.PhoneUi;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.utils.AppDialogUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/**
 * NEWTUBE(background-mode): on the phone "Play in background" lists only the two choices that
 * behave differently there. Stored values are never migrated: "Disabled" and the BACK variants
 * show as the choice they act as; only a pick writes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PhoneBackgroundPlaybackListTest {
    private Application app;
    private PlayerData playerData;
    private GeneralData generalData;
    private boolean phoneBefore;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app.getPackageManager())
                .setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, true);
        playerData = PlayerData.instance(app);
        generalData = GeneralData.instance(app);
        phoneBefore = PhoneUi.isEnabled();
        PhoneUi.setEnabled(true);
    }

    @After
    public void tearDown() {
        PhoneUi.setEnabled(phoneBefore);
    }

    private OptionCategory list(int mode, int shortcut) {
        playerData.setBackgroundMode(mode);
        generalData.setBackgroundPlaybackShortcut(shortcut);
        return AppDialogUtil.createBackgroundPlaybackCategory(app, playerData, generalData);
    }

    private String title(int res) {
        return app.getString(res);
    }

    @Test
    public void thePhoneListHasPictureInPictureAndOnlyAudio() {
        OptionCategory category = list(PlayerData.BACKGROUND_MODE_DEFAULT,
                GeneralData.BACKGROUND_PLAYBACK_SHORTCUT_HOME_BACK);

        assertEquals(2, category.options.size());
        assertEquals(title(com.liskovsoft.smartyoutubetv2.common.R.string.option_background_playback_pip),
                category.options.get(0).getTitle().toString());
        assertEquals(title(com.liskovsoft.smartyoutubetv2.common.R.string.option_background_playback_only_audio),
                category.options.get(1).getTitle().toString());
    }

    @Test
    public void disabledAndEveryPipVariantShowAsPictureInPicture() {
        int[][] stored = {
                {PlayerData.BACKGROUND_MODE_DEFAULT, GeneralData.BACKGROUND_PLAYBACK_SHORTCUT_HOME_BACK},
                {PlayerData.BACKGROUND_MODE_DEFAULT, GeneralData.BACKGROUND_PLAYBACK_SHORTCUT_BACK},
                {PlayerData.BACKGROUND_MODE_PIP, GeneralData.BACKGROUND_PLAYBACK_SHORTCUT_HOME},
                {PlayerData.BACKGROUND_MODE_PIP, GeneralData.BACKGROUND_PLAYBACK_SHORTCUT_BACK},
        };
        for (int[] value : stored) {
            OptionCategory category = list(value[0], value[1]);
            assertTrue(category.options.get(0).isSelected());
            assertFalse(category.options.get(1).isSelected());
            assertEquals("showing the list rewrites nothing", value[0], playerData.getBackgroundMode());
        }
    }

    @Test
    public void everyOnlyAudioVariantShowsAsOnlyAudio() {
        for (int shortcut : new int[] {GeneralData.BACKGROUND_PLAYBACK_SHORTCUT_HOME,
                GeneralData.BACKGROUND_PLAYBACK_SHORTCUT_HOME_BACK,
                GeneralData.BACKGROUND_PLAYBACK_SHORTCUT_BACK}) {
            OptionCategory category = list(PlayerData.BACKGROUND_MODE_SOUND, shortcut);
            assertFalse(category.options.get(0).isSelected());
            assertTrue(category.options.get(1).isSelected());
            assertEquals(shortcut, generalData.getBackgroundPlaybackShortcut());
        }
    }

    @Test
    public void pickingARowStoresItsHomeVariant() {
        OptionCategory category = list(PlayerData.BACKGROUND_MODE_DEFAULT,
                GeneralData.BACKGROUND_PLAYBACK_SHORTCUT_BACK);
        category.options.get(1).onSelect(true);
        assertEquals(PlayerData.BACKGROUND_MODE_SOUND, playerData.getBackgroundMode());
        assertEquals(GeneralData.BACKGROUND_PLAYBACK_SHORTCUT_HOME, generalData.getBackgroundPlaybackShortcut());

        category = AppDialogUtil.createBackgroundPlaybackCategory(app, playerData, generalData);
        category.options.get(0).onSelect(true);
        assertEquals(PlayerData.BACKGROUND_MODE_PIP, playerData.getBackgroundMode());
        assertEquals(GeneralData.BACKGROUND_PLAYBACK_SHORTCUT_HOME, generalData.getBackgroundPlaybackShortcut());
    }

    @Test
    public void withoutPipOnTheDeviceOnlyAudioIsLeftAndChecked() {
        Shadows.shadowOf(app.getPackageManager())
                .setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, false);
        OptionCategory category = list(PlayerData.BACKGROUND_MODE_DEFAULT,
                GeneralData.BACKGROUND_PLAYBACK_SHORTCUT_HOME_BACK);

        assertEquals(1, category.options.size());
        assertTrue(category.options.get(0).isSelected());
    }

    @Test
    public void theTvListIsUnchanged() {
        PhoneUi.setEnabled(false);
        OptionCategory category = list(PlayerData.BACKGROUND_MODE_DEFAULT,
                GeneralData.BACKGROUND_PLAYBACK_SHORTCUT_HOME_BACK);

        // Disabled + PiP x3 keys + Only audio x3 keys (Play behind is Android TV 5-7 only).
        assertEquals(7, category.options.size());
        OptionItem disabled = category.options.get(0);
        assertEquals(title(com.liskovsoft.smartyoutubetv2.common.R.string.option_background_playback_off),
                disabled.getTitle().toString());
        assertTrue(disabled.isSelected());
    }
}
