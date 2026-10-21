package com.newtube.mobile.update;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;

import com.liskovsoft.appupdatechecker2.AppUpdateChecker;
import com.liskovsoft.appupdatechecker2.AppUpdateCheckerListener;
import com.liskovsoft.appupdatechecker2.ReleaseNotes;
import com.liskovsoft.appupdatechecker2.UpdateInfo;
import com.liskovsoft.appupdatechecker2.other.downloadmanager.DownloadManager;
import com.liskovsoft.sharedutils.helpers.FileHelpers;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.MobileMainApplication;
import com.newtube.mobile.ui.common.MobileSnackbar;
import com.newtube.mobile.ui.update.MobileUpdateActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * NEWTUBE(update-flow): the phone's in-app update, one process-wide state that the update sheet
 * ({@code MobileUpdateActivity}), the You tab (badge + update row) and the launch check share.
 *
 * <p>What changed from the shared TV flow ({@code AppUpdatePresenter}): a check only reads the
 * manifest - nothing is downloaded until the user taps Update, and then with progress (the TV flow
 * downloaded ~65 MB in silence, with no feedback, even when the user asked); the release notes are
 * shown per version before the download; and after the update the app says it happened.</p>
 *
 * <p>Main thread only - the checker answers on it.</p>
 */
public final class AppUpdates implements AppUpdateCheckerListener {
    private static final String TAG = "AppUpdates";
    private static final String PREFS = "newtube_app_updates";
    /** A newer version a check found, so the You tab keeps offering it across launches. */
    private static final String KEY_KNOWN_CODE = "known_version_code";
    private static final String KEY_KNOWN_NAME = "known_version_name";
    /** The newest version whose update the user has looked at: it no longer badges the You tab. */
    private static final String KEY_SEEN_CODE = "seen_version_code";
    /** Handed to Android's installer; the first launch at (or past) it says "Updated to ...". */
    private static final String KEY_INSTALLING_CODE = "installing_version_code";
    private static final String KEY_INSTALLING_NOTES = "installing_notes";
    /** The notes of the update that was installed, for "What's new". */
    private static final String KEY_WHATS_NEW_NOTES = "whats_new_notes";
    /** The notes of the downloaded (not yet installed) update, for a sheet reopened after a restart. */
    private static final String KEY_READY_NOTES = "ready_notes";
    /** A result younger than this is shown as is when the update sheet opens; older ones re-check. */
    private static final long FRESH_MS = 10 * 60 * 1_000L;
    /** When the last check that reached the manifest (automatic or asked for) got its answer. */
    private static final String KEY_CHECKED_AT = "checked_at_ms";
    /**
     * How old that answer may be before the app checks again by itself, at launch and whenever Home
     * comes back to the front. The manifest is ~2 KB. The shared checker's own interval (12 h) and a
     * launch-only check meant a release could go unnoticed for a day, or for as long as Android
     * kept the app in memory: only Check for updates found it.
     */
    private static final long AUTO_CHECK_MS = 60 * 60 * 1_000L;
    /**
     * VOIDXDEV(update pop-up): a found update opens its sheet by itself on Home. "Later" brings it back
     * after this long for the same version (a newer version asks at once); "Don't ask again" turns
     * the pop-up off - the You tab's row and dot and Settings > About still offer the update.
     */
    private static final long PROMPT_AGAIN_MS = 24 * 60 * 60 * 1_000L;
    private static final String KEY_PROMPT_OFF = "prompt_off";
    private static final String KEY_PROMPTED_CODE = "prompted_version_code";
    private static final String KEY_PROMPTED_AT = "prompted_at_ms";
    /** An automatic check that got no answer is not retried sooner than this in one run. */
    private static final long AUTO_RETRY_MS = 5 * 60 * 1_000L;
    /** A user check joins an automatic one still waiting for its answer if it is younger than this. */
    private static final long AUTO_JOIN_MS = 30 * 1_000L;

    public enum Phase {
        IDLE, CHECKING, UP_TO_DATE, CHECK_FAILED, AVAILABLE, DOWNLOADING, DOWNLOAD_FAILED, READY
    }

    public interface Listener {
        void onUpdateStateChanged();
    }

    @SuppressLint("StaticFieldLeak") // holds the application context only
    private static AppUpdates sInstance;

    private final Context mContext;
    private final AppUpdateChecker mChecker;
    private final String[] mManifestUrls;
    private final SharedPreferences mPrefs;
    private final int mInstalledCode;
    private final String mInstalledName;
    private final List<Listener> mListeners = new ArrayList<>();

    private Phase mPhase = Phase.IDLE;
    @Nullable private UpdateInfo mInfo;
    private long mInfoAtMs;
    /** The check in flight was asked for by the user (a failure is shown, not swallowed). */
    private boolean mUserCheck;
    private long mBytes;
    private long mTotal = -1;
    private boolean mCancelling;
    private boolean mJustUpdated;
    /** elapsedRealtime of the last automatic check this run; 0 = none. */
    private long mAutoCheckAtMs;
    /** That check hasn't answered yet (the shared checker takes its answer as the current one). */
    private boolean mAutoCheckInFlight;

    public static AppUpdates instance(Context context) {
        if (sInstance == null) {
            sInstance = new AppUpdates(context.getApplicationContext());
        }

        return sInstance;
    }

    private AppUpdates(Context context) {
        mContext = context;
        mPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        mChecker = new AppUpdateChecker(context, this);
        mChecker.setDownloadOnCheck(false);
        mManifestUrls = manifestUrls(context);
        mInstalledCode = installedVersionCode(context);
        mInstalledName = installedVersionName(context);
        settleInstall();
    }

    /**
     * Debug builds can point the updater at a test manifest - an update-flow check needs a newer
     * version to be offered: {@code adb shell setprop debug.arc.update_manifest http://10.0.2.2:8000/newtube.json}
     */
    private static String[] manifestUrls(Context context) {
        String override = BuildConfig.DEBUG ? MobileMainApplication.getDebugSystemProperty("debug.arc.update_manifest") : "";

        if (!override.isEmpty()) {
            Log.w(TAG, "update manifest overridden (debug): " + override);
            return new String[] {override};
        }

        return context.getResources().getStringArray(R.array.update_urls);
    }

    /** First run after an update we handed to the installer: remember to say so, once. */
    private void settleInstall() {
        int installing = mPrefs.getInt(KEY_INSTALLING_CODE, 0);

        if (installing > 0 && mInstalledCode >= installing) {
            mJustUpdated = true;
            mPrefs.edit()
                    .putString(KEY_WHATS_NEW_NOTES, mPrefs.getString(KEY_INSTALLING_NOTES, null))
                    .remove(KEY_INSTALLING_CODE)
                    .remove(KEY_INSTALLING_NOTES)
                    .apply();
        }

        if (mPrefs.getInt(KEY_KNOWN_CODE, 0) <= mInstalledCode) {
            mPrefs.edit().remove(KEY_KNOWN_CODE).remove(KEY_KNOWN_NAME).apply();
        }
    }

    // ---------------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------------

    /** The launch check: quiet, and only when automatic checks are on and the last one is old. */
    public void checkOnLaunch() {
        mChecker.discardInstalledUpdate();
        checkIfDue("launch");
    }

    /**
     * Quiet automatic check (launch, Home back in front): only when automatic checks are on, the
     * user isn't in the flow, and the last answer is older than {@link #AUTO_CHECK_MS}. A newer
     * version shows up as the You tab's row and dot; nothing else is said.
     */
    public void checkIfDue(String reason) {
        if (!BuildConfig.IN_APP_UPDATES) {
            return; // store build (-Pfdroid): the store delivers updates, nothing is checked
        }

        // Only while nothing is on offer: with an update found (the You row already shows it) a
        // re-check could answer with another version while the user downloads the first, and the
        // shared checker would then validate the download against the wrong version.
        if ((mPhase != Phase.IDLE && mPhase != Phase.UP_TO_DATE) || !mChecker.isUpdateCheckEnabled()
                || !isOnline()) {
            return; // offline: the shared checker would toast "Internet connection not available!"
        }

        long lastAnswerAt = mPrefs.getLong(KEY_CHECKED_AT, 0);
        long now = SystemClock.elapsedRealtime();

        if (!isAutoCheckDue(System.currentTimeMillis(), lastAnswerAt, now, mAutoCheckAtMs)) {
            return;
        }

        mAutoCheckAtMs = now;
        mAutoCheckInFlight = true;
        Log.d(TAG, "auto check reason=" + reason + " last-answer-min="
                + (lastAnswerAt > 0 ? (System.currentTimeMillis() - lastAnswerAt) / 60_000 : -1));
        // "force" skips the shared checker's own 12 h interval; the time rule above replaces it. Its
        // user-asked mark only matters to a check that downloads the APK, which the phone's never
        // do (setDownloadOnCheck(false)). Answers through the listener; a failure stays quiet
        // (onUpdateError ignores it outside CHECKING).
        mChecker.forceCheckForUpdates(mManifestUrls);
    }

    /**
     * The automatic check's time rule: the last answer (wall clock, 0 = never) is older than
     * {@link #AUTO_CHECK_MS} - or lies in the future, after a clock change - and no automatic check
     * went out in this run (elapsedRealtime, 0 = none) in the last {@link #AUTO_RETRY_MS}.
     */
    static boolean isAutoCheckDue(long nowMs, long lastAnswerAtMs, long nowElapsedMs, long lastAutoCheckElapsedMs) {
        long sinceAnswer = nowMs - lastAnswerAtMs;

        if (lastAnswerAtMs > 0 && sinceAnswer >= 0 && sinceAnswer < AUTO_CHECK_MS) {
            return false;
        }

        return lastAutoCheckElapsedMs == 0 || nowElapsedMs - lastAutoCheckElapsedMs >= AUTO_RETRY_MS;
    }

    /** The update sheet opened: check, unless what it would show is already current. */
    public void refreshForScreen() {
        if (restoreDownloaded()) {
            return;
        }

        switch (mPhase) {
            case CHECKING:
            case DOWNLOADING:
            case READY:
                return;
            case AVAILABLE:
            case UP_TO_DATE:
                if (SystemClock.elapsedRealtime() - mInfoAtMs < FRESH_MS) {
                    return;
                }
                break;
        }

        check();
    }

    public void check() {
        if (mPhase == Phase.CHECKING || mPhase == Phase.DOWNLOADING) {
            return;
        }

        restoreDownloaded(); // so a failed check can still fall back to it

        mUserCheck = true;
        setPhase(Phase.CHECKING);

        if (mAutoCheckInFlight && SystemClock.elapsedRealtime() - mAutoCheckAtMs < AUTO_JOIN_MS) {
            return; // the automatic check's answer is this one's: one request, one answer
        }

        mChecker.forceCheckForUpdates(mManifestUrls);
    }

    /** The same test as the shared checker's offline toast (DownloadManager.isNetworkAvailable). */
    @SuppressWarnings("deprecation")
    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = cm != null ? cm.getActiveNetworkInfo() : null;
        return info != null && info.isConnected();
    }

    public void download() {
        if (mInfo == null || mPhase == Phase.DOWNLOADING || mPhase == Phase.CHECKING) {
            return;
        }

        if (mInfo.apkPath != null) {
            setPhase(Phase.READY);
            return;
        }

        mBytes = 0;
        mTotal = mInfo.downloadSize;
        mCancelling = false;
        setPhase(Phase.DOWNLOADING);
        mChecker.downloadUpdate();

        if (mPhase == Phase.DOWNLOADING) {
            // Keeps the transfer alive if the user leaves the app (Android cuts a background app's network)
            UpdateDownloadService.start(mContext);
        }
    }

    /**
     * After a restart nothing is in memory, but the APK an earlier download left may still be there:
     * offer it (READY) rather than make the user wait for - or, offline, fail - a check first.
     * @return true when it moved to READY
     */
    private boolean restoreDownloaded() {
        if (mInfo != null || mPhase != Phase.IDLE && mPhase != Phase.CHECK_FAILED) {
            return false;
        }

        UpdateInfo downloaded = mChecker.getDownloadedUpdate();

        if (downloaded == null) {
            return false;
        }

        List<ReleaseNotes> notes = fromJson(mPrefs.getString(KEY_READY_NOTES, null));
        mInfo = new UpdateInfo(downloaded.versionName, downloaded.versionCode, notes, null, -1, downloaded.apkPath);
        mInfoAtMs = SystemClock.elapsedRealtime();
        Log.d(TAG, "downloaded update restored: " + downloaded.versionName);
        setPhase(Phase.READY);
        return true;
    }

    /** Ends when the download reports back (at once: the transfer is aborted, not left to time out). */
    public void cancelDownload() {
        if (mPhase != Phase.DOWNLOADING || mCancelling) {
            return;
        }

        mCancelling = true;
        notifyListeners();
        mChecker.cancelDownload();
    }

    /**
     * Hands the downloaded APK to Android's installer, from {@code activity} so its screen stacks on
     * ours (Cancel comes back to the sheet). The caller has checked the "install unknown apps"
     * permission. @return false when there is nothing to install or no installer
     */
    public boolean install(Activity activity) {
        String path = mInfo != null ? mInfo.apkPath : null;
        Uri uri = path != null ? FileHelpers.getFileUri(activity, path) : null;

        if (uri == null) {
            return false;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        String installer = systemInstaller(activity.getPackageManager(), intent);

        if (installer != null) {
            intent.setPackage(installer);
        }

        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.e(TAG, "no installer for the update: " + e);
            return false;
        }

        // Only now: a launch that failed must not make a later install (by other means) look like ours
        mPrefs.edit()
                .putInt(KEY_INSTALLING_CODE, mInfo.versionCode)
                .putString(KEY_INSTALLING_NOTES, toJson(mInfo.newReleases))
                .apply();
        Log.d(TAG, "installer opened for " + mInfo.versionName);
        return true;
    }

    /**
     * The system app that installs {@code view}'s APK, or null to let Android choose, as before.
     * Apps that also open APK files (a file manager, Termux; three of them on the owner's Pixel 9,
     * 2026-09-29) otherwise turn the install into an "Open with" chooser, where any other pick
     * installs nothing. Only installers declare ACTION_INSTALL_PACKAGE, so a system one wins; else
     * the view intent's system handler if it is the only one (a system file manager may be one
     * too). The stmobile manifest's queries make them visible from Android 11.
     */
    @SuppressWarnings("deprecation") // ACTION_INSTALL_PACKAGE: resolved, never started
    @Nullable
    static String systemInstaller(PackageManager pm, Intent view) {
        Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(view.getData(), view.getType());
        List<String> installers = systemHandlers(pm, install);

        if (!installers.isEmpty()) {
            return installers.get(0);
        }

        List<String> viewers = systemHandlers(pm, view);
        return viewers.size() == 1 ? viewers.get(0) : null;
    }

    private static List<String> systemHandlers(PackageManager pm, Intent intent) {
        List<String> packages = new ArrayList<>();

        for (ResolveInfo info : pm.queryIntentActivities(intent, 0)) {
            if (info.activityInfo != null && info.activityInfo.applicationInfo != null
                    && (info.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    && !packages.contains(info.activityInfo.packageName)) {
                packages.add(info.activityInfo.packageName);
            }
        }

        return packages;
    }

    /** The newest version's update is no longer news: the You tab drops its badge. */
    public void markSeen() {
        int code = getUpdateVersionCode();

        if (code > mPrefs.getInt(KEY_SEEN_CODE, 0)) {
            mPrefs.edit().putInt(KEY_SEEN_CODE, code).apply();
            notifyListeners();
        }
    }

    /** true once, on the first launch after an update this app installed. */
    public boolean takeJustUpdated() {
        boolean justUpdated = mJustUpdated;
        mJustUpdated = false;
        return justUpdated;
    }

    // ---------------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------------

    public Phase getPhase() {
        return mPhase;
    }

    @Nullable
    public UpdateInfo getInfo() {
        return mInfo;
    }

    public long getDownloadedBytes() {
        return mBytes;
    }

    /** -1 when unknown. */
    public long getDownloadTotal() {
        return mTotal;
    }

    public boolean isCancelling() {
        return mCancelling;
    }

    public String getInstalledVersionName() {
        return mInstalledName;
    }

    /**
     * A newer version exists: found by a check in this run, or by an earlier launch's check while
     * automatic checks are still on (with them off, a remembered update doesn't keep nudging).
     */
    public boolean hasUpdate() {
        if (mInfo != null) {
            return mInfo.versionCode > mInstalledCode;
        }

        return mChecker.isUpdateCheckEnabled() && mPrefs.getInt(KEY_KNOWN_CODE, 0) > mInstalledCode;
    }

    /** VOIDXDEV(update pop-up): a freshly found update the user hasn't been asked about lately. */
    public boolean shouldPrompt() {
        if (!BuildConfig.IN_APP_UPDATES || isPromptOff() || mPhase != Phase.AVAILABLE || !hasUpdate()) {
            return false;
        }
        long since = System.currentTimeMillis() - mPrefs.getLong(KEY_PROMPTED_AT, 0);
        return getUpdateVersionCode() != mPrefs.getInt(KEY_PROMPTED_CODE, 0) || since < 0 || since >= PROMPT_AGAIN_MS;
    }

    public void markPrompted() {
        mPrefs.edit().putInt(KEY_PROMPTED_CODE, getUpdateVersionCode())
                .putLong(KEY_PROMPTED_AT, System.currentTimeMillis()).apply();
    }

    public boolean isPromptOff() {
        return mPrefs.getBoolean(KEY_PROMPT_OFF, false);
    }

    public void setPromptOff(boolean off) {
        mPrefs.edit().putBoolean(KEY_PROMPT_OFF, off).apply();
    }

    /** ...and the user hasn't opened its update sheet yet. */
    public boolean hasUnseenUpdate() {
        return hasUpdate() && getUpdateVersionCode() > mPrefs.getInt(KEY_SEEN_CODE, 0);
    }

    @Nullable
    public String getUpdateVersionName() {
        if (mInfo != null && mInfo.versionCode > mInstalledCode) {
            return mInfo.versionName;
        }

        return mPrefs.getString(KEY_KNOWN_NAME, null);
    }

    private int getUpdateVersionCode() {
        if (mInfo != null) {
            return mInfo.versionCode;
        }

        return mPrefs.getInt(KEY_KNOWN_CODE, 0);
    }

    /** The notes of the update installed last, newest version first (empty when unknown). */
    public List<ReleaseNotes> getWhatsNew() {
        return fromJson(mPrefs.getString(KEY_WHATS_NEW_NOTES, null));
    }

    public String getReleasePageUrl(String versionName) {
        return mContext.getString(R.string.about_source_code_url) + "/releases/tag/v" + versionName;
    }

    public boolean isOnMeteredNetwork() {
        return DownloadManager.isActiveNetworkMetered(mContext);
    }

    public void addListener(Listener listener) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    private void setPhase(Phase phase) {
        mPhase = phase;
        notifyListeners();
    }

    private void notifyListeners() {
        for (Listener listener : new ArrayList<>(mListeners)) {
            listener.onUpdateStateChanged();
        }
    }

    // ---------------------------------------------------------------------------------
    // AppUpdateCheckerListener
    // ---------------------------------------------------------------------------------

    @Override
    public void onUpdateAvailable(UpdateInfo info) {
        mUserCheck = false;
        mAutoCheckInFlight = false;

        if (mPhase == Phase.DOWNLOADING) {
            return; // a check that overlapped the download; the download's answer decides
        }

        mInfo = info;
        mInfoAtMs = SystemClock.elapsedRealtime();
        mPrefs.edit()
                .putInt(KEY_KNOWN_CODE, info.versionCode)
                .putString(KEY_KNOWN_NAME, info.versionName)
                .putLong(KEY_CHECKED_AT, System.currentTimeMillis())
                .apply();
        Log.d(TAG, "update available: " + info.versionName + (info.apkPath != null ? " (downloaded)" : ""));
        setPhase(info.apkPath != null ? Phase.READY : Phase.AVAILABLE);
    }

    @Override
    public void onUpToDate(UpdateInfo info) {
        mUserCheck = false;
        mAutoCheckInFlight = false;
        mInfo = info;
        mInfoAtMs = SystemClock.elapsedRealtime();
        mPrefs.edit().remove(KEY_KNOWN_CODE).remove(KEY_KNOWN_NAME)
                .putLong(KEY_CHECKED_AT, System.currentTimeMillis()).apply();
        setPhase(Phase.UP_TO_DATE);
    }

    @Override
    public void onUpdateError(Exception error) {
        boolean userCheck = mUserCheck;
        mUserCheck = false;
        mAutoCheckInFlight = false;

        if (mPhase != Phase.CHECKING) {
            return; // the launch check: disabled, not due yet, or offline - nothing to say
        }

        Log.e(TAG, "update check failed: " + error);

        if (mInfo != null && mInfo.apkPath != null && mInfo.versionCode > mInstalledCode) {
            setPhase(Phase.READY); // offline re-check: the downloaded update is still there to install
        } else {
            setPhase(userCheck ? Phase.CHECK_FAILED : Phase.IDLE);
        }
    }

    @Override
    public void onDownloadProgress(long bytes, long total) {
        if (mPhase != Phase.DOWNLOADING) {
            return;
        }

        mBytes = bytes;
        mTotal = total;
        notifyListeners();
    }

    @Override
    public void onUpdateFound(String versionName, List<String> changelog, String apkPath) {
        // The download finished and the APK checked out as this app at the new version
        mCancelling = false;
        mInfo = mInfo != null ? mInfo.withApkPath(apkPath) : null;

        if (mInfo != null) {
            mPrefs.edit().putString(KEY_READY_NOTES, toJson(mInfo.newReleases)).apply();
        }

        setPhase(mInfo != null ? Phase.READY : Phase.IDLE);

        if (mInfo != null && !MobileUpdateActivity.isShowing()) {
            // The sheet was closed during the download: say it's done, with the next step one tap away -
            // on the screen in front, or as a notification when the user has left the app
            if (Helpers.isAppInForeground()) {
                MobileSnackbar.show(mContext, mContext.getString(R.string.mobile_update_downloaded),
                        mContext.getString(R.string.mobile_update_install), () -> MobileUpdateActivity.startInstall(mContext),
                        MobileSnackbar.NOTICE_DURATION_MS);
            } else {
                UpdateDownloadService.postReady(mContext, mInfo);
            }
        }
    }

    @Override
    public void onDownloadError(Exception error) {
        if (mPhase != Phase.DOWNLOADING) {
            return;
        }

        boolean cancelled = mCancelling;
        mCancelling = false;

        if (!cancelled) {
            Log.e(TAG, "update download failed: " + error);
        }

        setPhase(cancelled ? Phase.AVAILABLE : Phase.DOWNLOAD_FAILED);
    }

    // ---------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private static int installedVersionCode(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (NameNotFoundException e) {
            return 0;
        }
    }

    private static String installedVersionName(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (NameNotFoundException e) {
            return "";
        }
    }

    private static String toJson(List<ReleaseNotes> releases) {
        JSONArray array = new JSONArray();

        try {
            for (ReleaseNotes release : releases) {
                array.put(new JSONObject()
                        .put("name", release.versionName)
                        .put("code", release.versionCode)
                        .put("lines", new JSONArray(release.lines)));
            }
        } catch (JSONException e) {
            return null;
        }

        return array.toString();
    }

    private static List<ReleaseNotes> fromJson(@Nullable String json) {
        if (json == null) {
            return Collections.emptyList();
        }

        List<ReleaseNotes> releases = new ArrayList<>();

        try {
            JSONArray array = new JSONArray(json);

            for (int i = 0; i < array.length(); i++) {
                JSONObject release = array.getJSONObject(i);
                JSONArray lines = release.getJSONArray("lines");
                List<String> text = new ArrayList<>();

                for (int j = 0; j < lines.length(); j++) {
                    text.add(lines.getString(j));
                }

                releases.add(new ReleaseNotes(release.getString("name"), release.getInt("code"), text));
            }
        } catch (JSONException e) {
            return Collections.emptyList();
        }

        return releases;
    }
}
