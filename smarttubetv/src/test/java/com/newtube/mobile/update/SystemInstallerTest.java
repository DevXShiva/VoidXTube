package com.newtube.mobile.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/**
 * The update goes straight to Android's installer: on the owner's Pixel 9 (2026-09-29) three apps
 * that also open APK files (DeepSeek, Sticker Maker, Termux) turned the install into a chooser.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = android.app.Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SystemInstallerTest {
    private static final String APK = "application/vnd.android.package-archive";
    private PackageManager pm;
    private Intent view;
    private Intent install;

    @Before
    public void setUp() {
        pm = RuntimeEnvironment.getApplication().getPackageManager();
        Uri uri = Uri.parse("content://io.github.aleixrodriala.arc.fileprovider/update.apk");
        view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK);
        //noinspection deprecation
        install = new Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, APK);
    }

    @Test
    public void theSystemInstallerWinsOverAppsThatAlsoOpenApks() {
        handles(view, "com.termux", false);
        handles(view, "com.deepseek.chat", false);
        handles(view, "com.google.android.packageinstaller", true);
        handles(install, "com.google.android.packageinstaller", true);
        assertEquals("com.google.android.packageinstaller", AppUpdates.systemInstaller(pm, view));
    }

    @Test
    public void aSystemFileManagerThatOpensApksIsNotTakenForTheInstaller() {
        handles(view, "com.sec.android.app.myfiles", true);
        handles(view, "com.samsung.android.packageinstaller", true);
        handles(install, "com.samsung.android.packageinstaller", true);
        assertEquals("com.samsung.android.packageinstaller", AppUpdates.systemInstaller(pm, view));
    }

    @Test
    public void withoutAnInstallPackageHandlerTheOnlySystemViewerIsUsed() {
        handles(view, "com.termux", false);
        handles(view, "com.android.packageinstaller", true);
        assertEquals("com.android.packageinstaller", AppUpdates.systemInstaller(pm, view));
    }

    @Test
    public void anAmbiguousOrUnknownInstallerLeavesTheChoiceToAndroid() {
        assertNull(AppUpdates.systemInstaller(pm, view));
        handles(view, "com.sec.android.app.myfiles", true);
        handles(view, "com.android.packageinstaller", true);
        assertNull(AppUpdates.systemInstaller(pm, view));
    }

    private void handles(Intent intent, String pkg, boolean system) {
        ResolveInfo info = new ResolveInfo();
        info.activityInfo = new ActivityInfo();
        info.activityInfo.packageName = pkg;
        info.activityInfo.name = pkg + ".Install";
        info.activityInfo.applicationInfo = new ApplicationInfo();
        info.activityInfo.applicationInfo.packageName = pkg;
        info.activityInfo.applicationInfo.flags = system ? ApplicationInfo.FLAG_SYSTEM : 0;
        shadowOf(pm).addResolveInfoForIntent(intent, info);
    }
}
