package com.liskovsoft.smartyoutubetv2.common.app.presenters.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** NEWTUBE(notifications): the phone hides the Notifications section everywhere a section is listed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SidebarServiceNotificationsTest {
    private SidebarService mSidebar;

    @Before
    public void setUp() {
        mSidebar = SidebarService.instance(RuntimeEnvironment.getApplication());
        // A user who enabled Notifications and boots to it (stored prefs).
        mSidebar.enableSection(MediaGroup.TYPE_NOTIFICATIONS, true);
        mSidebar.setBootSectionId(MediaGroup.TYPE_NOTIFICATIONS);
    }

    @After
    public void tearDown() {
        SidebarService.setNotificationsSectionHidden(false);
    }

    @Test
    public void theTvKeepsTheSection() {
        assertTrue(mSidebar.isSectionPinned(MediaGroup.TYPE_NOTIFICATIONS));
        assertTrue(pinned(MediaGroup.TYPE_NOTIFICATIONS));
        assertTrue(mSidebar.getDefaultSections().containsValue(MediaGroup.TYPE_NOTIFICATIONS));
        assertEquals(MediaGroup.TYPE_NOTIFICATIONS, mSidebar.getBootSectionId());
    }

    @Test
    public void thePhoneHidesItFromEveryListAndBootsToHome() {
        SidebarService.setNotificationsSectionHidden(true);

        assertFalse("You panel / nav: no pinned Notifications", pinned(MediaGroup.TYPE_NOTIFICATIONS));
        assertFalse(mSidebar.isSectionPinned(MediaGroup.TYPE_NOTIFICATIONS));
        assertFalse("Set-up sections / Boot to section", mSidebar.getDefaultSections().containsValue(MediaGroup.TYPE_NOTIFICATIONS));
        assertEquals(MediaGroup.TYPE_HOME, mSidebar.getBootSectionId());

        assertTrue("the other sections stay", mSidebar.getDefaultSections().containsValue(MediaGroup.TYPE_HOME));
        assertTrue(mSidebar.isSectionPinned(MediaGroup.TYPE_HOME));

        // The stored choice is untouched: turning the gate off brings it all back.
        SidebarService.setNotificationsSectionHidden(false);
        assertTrue(pinned(MediaGroup.TYPE_NOTIFICATIONS));
        assertEquals(MediaGroup.TYPE_NOTIFICATIONS, mSidebar.getBootSectionId());
    }

    private boolean pinned(int sectionId) {
        for (Video item : mSidebar.getPinnedItems()) {
            if (item != null && item.sectionId == sectionId) {
                return true;
            }
        }
        return false;
    }
}
