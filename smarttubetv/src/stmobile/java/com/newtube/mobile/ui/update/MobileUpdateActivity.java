package com.newtube.mobile.ui.update;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.format.Formatter;
import android.text.style.BulletSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.liskovsoft.appupdatechecker2.ReleaseNotes;
import com.liskovsoft.appupdatechecker2.UpdateInfo;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Haptics;
import com.newtube.mobile.ui.common.MobileActivity;
import com.newtube.mobile.update.AppUpdates;
import com.newtube.mobile.update.UpdateDownloadService;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;

/**
 * NEWTUBE(update-flow): the update sheet. A transparent screen that hosts one Material bottom sheet,
 * so every way in - Settings -> About -> Check for updates, the You tab's update row, the "Update
 * downloaded" and "Updated to ..." snackbars - opens the same thing, and it is still there when the
 * user comes back from Android's "Install unknown apps" screen.
 *
 * <p>The sheet follows {@link AppUpdates}: checking -> up to date (with that version's notes) or
 * update available (notes per version, size, Later / Update) -> downloading with progress (Cancel)
 * -> the installer opens by itself. The one-time "allow installs" permission is explained here and
 * the install resumes on return; the system's own detour dropped the install and left the user
 * to find the button again. "What's new" after an update reuses the sheet.</p>
 */
public class MobileUpdateActivity extends MobileActivity implements AppUpdates.Listener {
    private static final String EXTRA_MODE = "newtube:update_mode";
    /** VOIDXDEV(update pop-up): opened by itself, so it offers "Don't ask again". */
    private static final String EXTRA_PROMPT = "voidx:update_prompt";
    /** The You tab's update row: what is known (checks only when that is old). */
    private static final int MODE_UPDATE = 0;
    /** Check for updates: always asks the server. */
    private static final int MODE_CHECK = 1;
    /** The "Update downloaded" snackbar: straight to the installer. */
    private static final int MODE_INSTALL = 2;
    /** After an update: the installed version's notes. */
    private static final int MODE_WHATS_NEW = 3;
    /** Share of the screen the notes may take before they scroll (the buttons stay visible). */
    private static final float NOTES_MAX_HEIGHT_FRACTION = 0.45f;

    /** The sheet on screen, between onStart and onStop - see {@link #isShowing()}. */
    @Nullable private static WeakReference<MobileUpdateActivity> sShown;

    private int mMode;
    private MaterialButton mDontAsk;
    private AppUpdates mUpdates;
    private BottomSheetDialog mSheet;
    private TextView mTitle;
    private TextView mSubtitle;
    private LinearProgressIndicator mProgress;
    private TextView mProgressText;
    private TextView mMessage;
    private LinearLayout mNotes;
    private TextView mReleaseNotesLink;
    private MaterialButton mSecondary;
    private MaterialButton mPrimary;
    private MaxHeightScrollView mScroll;
    /** What the notes currently show, so a progress tick doesn't rebuild them. */
    private List<ReleaseNotes> mShownNotes;

    /** The user tapped Update (or Install) here: open the installer as soon as the APK is ready. */
    private boolean mInstallWhenReady;
    /** Showing the "allow installs" explanation. */
    private boolean mAskingPermission;
    /** Sent to Android's "Install unknown apps" screen; install on return if it was granted. */
    private boolean mAwaitingPermission;
    private boolean mInstallerMissing;
    /**
     * Between onResume and onPause. Not the Lifecycle's RESUMED: androidx reaches that only after
     * onResume returns, so an install asked for from onResume (the "ready" notification) never ran.
     */
    private boolean mResumed;

    /** Settings -> About -> Check for updates. */
    public static void startCheck(Context context) {
        start(context, MODE_CHECK);
    }

    /** The You tab's update row. */
    public static void start(Context context) {
        start(context, MODE_UPDATE);
    }

    /** VOIDXDEV(update pop-up): Home found an update by itself (AppUpdates.shouldPrompt). */
    public static void startPrompt(Context context) {
        context.startActivity(intent(context, MODE_UPDATE).putExtra(EXTRA_PROMPT, true));
    }

    public static void startInstall(Context context) {
        start(context, MODE_INSTALL);
    }

    public static void startWhatsNew(Context context) {
        start(context, MODE_WHATS_NEW);
    }

    private static void start(Context context, int mode) {
        Intent intent = intent(context, mode);

        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }

        context.startActivity(intent);
    }

    /** For the download notification. */
    public static Intent showIntent(Context context) {
        return intent(context, MODE_UPDATE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** For the "ready to install" notification. */
    public static Intent installIntent(Context context) {
        return intent(context, MODE_INSTALL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static Intent intent(Context context, int mode) {
        return new Intent(context, MobileUpdateActivity.class).putExtra(EXTRA_MODE, mode);
    }

    /**
     * The sheet is on screen and staying (the "Update downloaded" notices would be redundant). A
     * dismissed sheet is still started until onStop, a few hundred ms later - long enough for a
     * download that completes then to announce itself nowhere.
     */
    public static boolean isShowing() {
        MobileUpdateActivity shown = sShown != null ? sShown.get() : null;
        return shown != null && !shown.isFinishing() && shown.mSheet != null && shown.mSheet.isShowing();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mUpdates = AppUpdates.instance(this);
        mMode = getIntent().getIntExtra(EXTRA_MODE, MODE_UPDATE);

        @SuppressLint("InflateParams") // a dialog's content: BottomSheetDialog.setContentView gives it its parent
        View content = LayoutInflater.from(this).inflate(R.layout.sheet_mobile_update, null);
        mTitle = content.findViewById(R.id.update_sheet_title);
        ((android.widget.ImageView) content.findViewById(R.id.mobile_update_icon))
                .setImageResource(com.newtube.mobile.ui.common.AppIcon.launcherIcon(this));
        mSubtitle = content.findViewById(R.id.update_sheet_subtitle);
        mProgress = content.findViewById(R.id.update_sheet_progress);
        mProgressText = content.findViewById(R.id.update_sheet_progress_text);
        mMessage = content.findViewById(R.id.update_sheet_message);
        mNotes = content.findViewById(R.id.update_sheet_notes);
        mReleaseNotesLink = content.findViewById(R.id.update_sheet_release_notes);
        mSecondary = content.findViewById(R.id.update_sheet_secondary);
        mPrimary = content.findViewById(R.id.update_sheet_primary);
        mDontAsk = content.findViewById(R.id.update_sheet_dont_ask);
        mDontAsk.setOnClickListener(v -> {
            mUpdates.setPromptOff(true);
            android.widget.Toast.makeText(this, R.string.mobile_update_dont_ask_done, android.widget.Toast.LENGTH_LONG).show();
            dismiss();
        });
        mScroll = content.findViewById(R.id.update_sheet_scroll);
        mScroll.setMaxHeight(Math.round(screenHeightPx() * NOTES_MAX_HEIGHT_FRACTION));

        mSheet = Haptics.watch(new BottomSheetDialog(this));
        mSheet.setContentView(content);
        mSheet.setOnDismissListener(dialog -> finish());
        // The whole sheet or nothing: a half-open peek hid the buttons under a long list of notes
        mSheet.getBehavior().setSkipCollapsed(true);
        mSheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);

        mUpdates.addListener(this);

        if (mMode != MODE_WHATS_NEW) {
            mInstallWhenReady = mMode == MODE_INSTALL;
            refresh(mMode);
            mUpdates.markSeen();
        }
        // NEWTUBE(theme): a recreation (a theme change, say) keeps the user's Update tap and the
        // trip to "Install unknown apps", so the install still follows on its own.
        if (savedInstanceState != null) {
            mInstallWhenReady |= savedInstanceState.getBoolean(STATE_INSTALL_WHEN_READY);
            mAskingPermission = savedInstanceState.getBoolean(STATE_ASKING_PERMISSION);
            mAwaitingPermission = savedInstanceState.getBoolean(STATE_AWAITING_PERMISSION);
        }

        render();
        mSheet.show();
    }

    private static final String STATE_INSTALL_WHEN_READY = "newtube:update_install_when_ready";
    private static final String STATE_ASKING_PERMISSION = "newtube:update_asking_permission";
    private static final String STATE_AWAITING_PERMISSION = "newtube:update_awaiting_permission";

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_INSTALL_WHEN_READY, mInstallWhenReady);
        outState.putBoolean(STATE_ASKING_PERMISSION, mAskingPermission);
        outState.putBoolean(STATE_AWAITING_PERMISSION, mAwaitingPermission);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        int mode = intent.getIntExtra(EXTRA_MODE, MODE_UPDATE);

        if (mode == MODE_WHATS_NEW) {
            mMode = MODE_WHATS_NEW;
        } else {
            mMode = MODE_UPDATE;
            mInstallWhenReady |= mode == MODE_INSTALL;
            refresh(mode);
        }

        render();
        installIfAsked();
    }

    private void refresh(int mode) {
        if (mode == MODE_CHECK) {
            mUpdates.check(); // no-op while a check or the download is running
        } else {
            mUpdates.refreshForScreen();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        sShown = new WeakReference<>(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mResumed = true;
        // Back in front (a Home press while the download finished posted it): the sheet says it now
        UpdateDownloadService.clearReady(this);

        if (mAwaitingPermission && canInstallPackages()) {
            // Back from "Install unknown apps" with the switch on: carry on with the install
            mAwaitingPermission = false;
            mAskingPermission = false;
            install();
            return;
        }

        installIfAsked();
    }

    @Override
    protected void onPause() {
        mResumed = false;
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();

        if (sShown != null && sShown.get() == this) {
            sShown = null;
        }
    }

    @Override
    protected void onDestroy() {
        mUpdates.removeListener(this);

        if (mSheet != null) {
            mSheet.setOnDismissListener(null);
            mSheet.dismiss();
        }

        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Rotated in place (configChanges): a portrait-sized cap pushed the buttons off a landscape screen
        mScroll.setMaxHeight(Math.round(screenHeightPx() * NOTES_MAX_HEIGHT_FRACTION));
    }

    @Override
    protected boolean isBackStackScreen() {
        return false;
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0); // the sheet already slid away
    }

    @Override
    public void onUpdateStateChanged() {
        render();
        installIfAsked();

        if (mMode != MODE_WHATS_NEW) {
            mUpdates.markSeen(); // a check from this sheet can find a newer version than the badge's
        }
    }

    // ---------------------------------------------------------------------------------
    // Install
    // ---------------------------------------------------------------------------------

    private void installIfAsked() {
        if (mInstallWhenReady && mUpdates.getPhase() == AppUpdates.Phase.READY && mResumed) {
            mInstallWhenReady = false;
            install();
        }
    }

    private void install() {
        mInstallerMissing = false;

        if (!canInstallPackages()) {
            mAskingPermission = true;
            render();
            return;
        }

        mAskingPermission = false;
        mInstallerMissing = !mUpdates.install(this);
        render();
    }

    private void openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < 26) {
            install();
            return;
        }

        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            mAwaitingPermission = true;
        } catch (ActivityNotFoundException e) {
            // No such screen on this build: let the installer ask in its own way
            mAskingPermission = false;
            mInstallerMissing = !mUpdates.install(this);
            render();
        }
    }

    private boolean canInstallPackages() {
        return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
    }

    // ---------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------

    private void render() {
        if (mMode == MODE_WHATS_NEW) {
            renderWhatsNew();
            return;
        }

        UpdateInfo info = mUpdates.getInfo();
        String newVersion = info != null ? info.versionName : "";

        if (mAskingPermission) {
            show(getString(R.string.mobile_update_permission_title), getString(R.string.mobile_update_app_version, newVersion),
                    getString(R.string.mobile_update_permission_body), null);
            hideProgress();
            setButtons(R.string.mobile_update_not_now, v -> {
                mAskingPermission = false;
                render();
            }, R.string.mobile_update_open_settings, v -> openInstallPermissionSettings());
            return;
        }

        if (mInstallerMissing) {
            show(getString(R.string.mobile_update_install_failed_title), getString(R.string.mobile_update_app_version, newVersion),
                    getString(R.string.mobile_update_download_failed_body, newVersion), null);
            hideProgress();
            setButtons(R.string.mobile_update_github, v -> openReleasePage(newVersion),
                    R.string.mobile_update_try_again, v -> install());
            return;
        }

        switch (mUpdates.getPhase()) {
            case IDLE:
            case CHECKING:
                show(getString(R.string.mobile_update_checking), getString(R.string.mobile_update_version,
                        mUpdates.getInstalledVersionName()), null, null);
                showProgress(true, 0);
                setButtons(0, null, 0, null);
                break;
            case UP_TO_DATE:
                show(getString(R.string.mobile_update_up_to_date), getString(R.string.mobile_update_version,
                        mUpdates.getInstalledVersionName()), null,
                        info != null && info.installedRelease != null ? Collections.singletonList(info.installedRelease) : null);
                hideProgress();
                setButtons(0, null, R.string.mobile_update_done, v -> dismiss());
                break;
            case CHECK_FAILED:
                show(getString(R.string.mobile_update_check_failed_title), getString(R.string.mobile_update_version,
                        mUpdates.getInstalledVersionName()), getString(R.string.mobile_update_check_failed_body), null);
                hideProgress();
                setButtons(R.string.mobile_update_not_now, v -> dismiss(), R.string.mobile_update_try_again, v -> mUpdates.check());
                break;
            case AVAILABLE:
                show(getString(R.string.mobile_update_available_title), versionAndSize(info),
                        mUpdates.isOnMeteredNetwork() ? getString(R.string.mobile_update_metered) : null, info.newReleases);
                hideProgress();
                setButtons(R.string.mobile_update_later, v -> dismiss(), R.string.mobile_update_update, v -> {
                    mInstallWhenReady = true;
                    mUpdates.download();
                });
                mDontAsk.setVisibility(getIntent().getBooleanExtra(EXTRA_PROMPT, false) ? View.VISIBLE : View.GONE);
                break;
            case DOWNLOADING:
                show(getString(R.string.mobile_update_downloading_title), getString(R.string.mobile_update_app_version, newVersion),
                        null, info.newReleases);
                renderDownloadProgress();
                setButtons(R.string.mobile_update_cancel, v -> mUpdates.cancelDownload(), 0, null);
                mSecondary.setEnabled(!mUpdates.isCancelling());
                break;
            case DOWNLOAD_FAILED:
                show(getString(R.string.mobile_update_download_failed_title), getString(R.string.mobile_update_app_version, newVersion),
                        getString(R.string.mobile_update_download_failed_body, newVersion), info.newReleases);
                hideProgress();
                setButtons(R.string.mobile_update_github, v -> openReleasePage(newVersion), R.string.mobile_update_try_again, v -> {
                    mInstallWhenReady = true;
                    mUpdates.download();
                });
                break;
            case READY:
                show(getString(R.string.mobile_update_ready_title), getString(R.string.mobile_update_app_version, newVersion),
                        null, info.newReleases);
                hideProgress();
                setButtons(R.string.mobile_update_later, v -> dismiss(), R.string.mobile_update_install, v -> install());
                break;
        }
    }

    private void renderWhatsNew() {
        List<ReleaseNotes> notes = mUpdates.getWhatsNew();
        show(getString(R.string.mobile_update_whats_new), getString(R.string.mobile_update_app_version,
                mUpdates.getInstalledVersionName()), null, notes);
        hideProgress();
        setButtons(0, null, R.string.mobile_update_done, v -> dismiss());
    }

    private String versionAndSize(UpdateInfo info) {
        if (info.downloadSize > 0) {
            return getString(R.string.mobile_update_app_version_size, info.versionName,
                    Formatter.formatShortFileSize(this, info.downloadSize));
        }

        return getString(R.string.mobile_update_app_version, info.versionName);
    }

    private void renderDownloadProgress() {
        long bytes = mUpdates.getDownloadedBytes();
        long total = mUpdates.getDownloadTotal();

        if (total > 0) {
            showProgress(false, (int) Math.min(100, bytes * 100 / total));
            mProgressText.setText(getString(R.string.mobile_update_progress,
                    Formatter.formatShortFileSize(this, bytes), Formatter.formatShortFileSize(this, total)));
        } else {
            showProgress(true, 0);
            mProgressText.setText(Formatter.formatShortFileSize(this, bytes));
        }

        mProgressText.setVisibility(View.VISIBLE);
    }

    private void show(CharSequence title, CharSequence subtitle, @Nullable CharSequence message,
                      @Nullable List<ReleaseNotes> notes) {
        mTitle.setText(title);
        mSubtitle.setText(subtitle);
        mMessage.setText(message);
        mMessage.setVisibility(message != null ? View.VISIBLE : View.GONE);

        if (notes == null || notes.isEmpty()) {
            notes = Collections.emptyList();
        }

        if (!notes.equals(mShownNotes)) {
            mShownNotes = notes;
            mNotes.removeAllViews();

            for (ReleaseNotes release : notes) {
                addRelease(release);
            }
        }

        if (!notes.isEmpty()) {
            String version = notes.get(0).versionName;
            mReleaseNotesLink.setVisibility(View.VISIBLE);
            mReleaseNotesLink.setOnClickListener(v -> openReleasePage(version));
        } else {
            mReleaseNotesLink.setVisibility(View.GONE);
        }
    }

    /** "What's new in 1.10.5" and its lines as a bullet list (wrapped lines hang under the text). */
    private void addRelease(ReleaseNotes release) {
        TextView header = new TextView(this);
        // Under the "What's new" title, "What's new in 1.10.5" would say it twice
        header.setText(getString(mMode == MODE_WHATS_NEW ? R.string.mobile_update_version : R.string.mobile_update_whats_new_in,
                release.versionName));
        header.setTextColor(ContextCompat.getColor(this, R.color.mobile_color_on_surface));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        header.setTypeface(header.getTypeface(), android.graphics.Typeface.BOLD);
        header.setPadding(0, dp(16), 0, dp(4));
        mNotes.addView(header);

        int color = ContextCompat.getColor(this, R.color.mobile_color_on_surface_secondary);

        for (String line : release.lines) {
            SpannableString text = new SpannableString(line);
            BulletSpan bullet = Build.VERSION.SDK_INT >= 28 ? new BulletSpan(dp(10), color, dp(2)) : new BulletSpan(dp(10), color);
            text.setSpan(bullet, 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            TextView item = new TextView(this);
            item.setText(text);
            item.setTextColor(color);
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            item.setLineSpacing(0, 1.15f);
            item.setPadding(0, dp(3), 0, dp(3));
            mNotes.addView(item);
        }
    }

    private void showProgress(boolean indeterminate, int percent) {
        if (mProgress.isIndeterminate() != indeterminate) {
            // Material refuses to switch modes on a visible indicator
            mProgress.setVisibility(View.INVISIBLE);
            mProgress.setIndeterminate(indeterminate);
        }

        if (!indeterminate) {
            mProgress.setProgressCompat(percent, true);
        }

        mProgress.setVisibility(View.VISIBLE);
    }

    private void hideProgress() {
        mProgress.setVisibility(View.GONE);
        mProgressText.setVisibility(View.GONE);
    }

    private void setButtons(int secondaryText, @Nullable View.OnClickListener secondary,
                            int primaryText, @Nullable View.OnClickListener primary) {
        bind(mSecondary, secondaryText, secondary);
        bind(mPrimary, primaryText, primary);
        mDontAsk.setVisibility(View.GONE); // AVAILABLE shows it again for a pop-up
    }

    private static void bind(MaterialButton button, int text, @Nullable View.OnClickListener listener) {
        button.setEnabled(true);

        if (text == 0) {
            button.setVisibility(View.GONE);
            button.setOnClickListener(null);
            return;
        }

        button.setText(text);
        button.setOnClickListener(listener);
        button.setVisibility(View.VISIBLE);
    }

    private void openReleasePage(String versionName) {
        Utils.openLinkExt(this, mUpdates.getReleasePageUrl(versionName));
    }

    private void dismiss() {
        mSheet.dismiss(); // -> finish()
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    private int screenHeightPx() {
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        return metrics.heightPixels;
    }
}
