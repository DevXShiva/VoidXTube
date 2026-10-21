package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/**
 * NEWTUBE(menu-pip): the player menu's Picture-in-picture row popped straight back to full screen
 * (1.11.0): the screen under the player regained focus as the PiP opened, and that focus is the
 * launcher-restore hook. A menu PiP is "in-app" until none of our screens is on screen any more;
 * only then does a focus mean the user came back from the launcher.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class MenuPipRestoreTest {

    @Before
    public void setUp() {
        SystemPipBridge.install(RuntimeEnvironment.getApplication());
        SystemPipBridge.onPipEnded();
    }

    @After
    public void tearDown() {
        SystemPipBridge.onPipEnded();
    }

    private static ActivityController<Activity> startScreen() {
        return Robolectric.buildActivity(Activity.class).create().start().resume();
    }

    @Test
    public void aPinnedPlayerIsRestoredByALauncherReturn() {
        assertTrue(SystemPipBridge.shouldRestore(/* playerPinned= */ true, /* inAppPip= */ false));
        assertFalse(SystemPipBridge.shouldRestore(false, false));
    }

    @Test
    public void theScreenUnderAMenuPipRegainingFocusDoesNotRestoreIt() {
        ActivityController<Activity> home = startScreen();
        SystemPipBridge.onMenuPipEntered(/* screenUnderPlayer= */ true);

        assertTrue(SystemPipBridge.isInAppPip());
        assertFalse(SystemPipBridge.shouldRestore(true, SystemPipBridge.isInAppPip()));
        home.pause().stop().destroy();
    }

    @Test
    public void movingBetweenOurScreensKeepsTheMenuPipInApp() {
        ActivityController<Activity> home = startScreen();
        SystemPipBridge.onMenuPipEntered(true);

        // Home -> Search: the new screen starts before the old one stops.
        ActivityController<Activity> search = startScreen();
        home.pause().stop();
        assertTrue(SystemPipBridge.isInAppPip());

        search.pause().stop().destroy();
        home.destroy();
    }

    @Test
    public void leavingTheAppHandsTheFocusBackToTheLauncherRestore() {
        ActivityController<Activity> home = startScreen();
        SystemPipBridge.onMenuPipEntered(true);

        home.pause().stop(); // Home button: none of our screens is on screen
        assertFalse(SystemPipBridge.isInAppPip());

        home.start().resume(); // launcher tap
        assertTrue(SystemPipBridge.shouldRestore(true, SystemPipBridge.isInAppPip()));
        home.pause().stop().destroy();
    }

    /** A screen whose stop belongs to a recreation (configuration change). */
    public static class RecreatingActivity extends Activity {
        boolean changing;

        @Override
        public boolean isChangingConfigurations() {
            return changing;
        }
    }

    @Test
    public void recreatingTheScreenUnderAMenuPipKeepsItInApp() {
        // Dark mode, locale, font size or a fold while the player floats: the screen under it
        // stops for a moment (count 0) and its replacement starts and gains focus.
        ActivityController<RecreatingActivity> home =
                Robolectric.buildActivity(RecreatingActivity.class).create().start().resume();
        SystemPipBridge.onMenuPipEntered(true);

        home.get().changing = true;
        home.pause().stop();
        assertTrue("a recreation is not the user leaving", SystemPipBridge.isInAppPip());

        ActivityController<Activity> replacement = startScreen();
        assertFalse("the replacement's focus is no launcher return",
                SystemPipBridge.shouldRestore(true, SystemPipBridge.isInAppPip()));

        replacement.pause().stop(); // then the Home button
        assertFalse(SystemPipBridge.isInAppPip());
        replacement.destroy();
        home.destroy();
    }

    @Test
    public void onlyAStopThatEmptiesTheAppWithoutARecreationIsLeaving() {
        assertTrue(SystemPipBridge.leftApp(0, /* changingConfigurations= */ false));
        assertFalse(SystemPipBridge.leftApp(0, true));
        assertFalse(SystemPipBridge.leftApp(1, false));
    }

    @Test
    public void aStalePinnedWordAfterAnAbortedEntryIsNotTrusted() {
        assertTrue(MobilePlaybackActivity.pinnedForRestore(/* platformSaysPinned= */ true, /* stateStale= */ false));
        assertFalse("undone entry, no callbacks: the fullscreen player still reads as pinned",
                MobilePlaybackActivity.pinnedForRestore(true, true));
        assertFalse(MobilePlaybackActivity.pinnedForRestore(false, false));
    }

    @Test
    public void aPlayerAloneInItsTaskLeavesNothingInAppBehind() {
        // A cold share link: the whole task goes into PiP, the launcher is what shows.
        SystemPipBridge.onMenuPipEntered(/* screenUnderPlayer= */ false);
        assertFalse(SystemPipBridge.isInAppPip());
    }

    @Test
    public void theEndOfThePipClearsTheMark() {
        ActivityController<Activity> home = startScreen();
        SystemPipBridge.onMenuPipEntered(true);
        SystemPipBridge.onPipEnded();
        assertFalse(SystemPipBridge.isInAppPip());
        home.pause().stop().destroy();
    }
}
