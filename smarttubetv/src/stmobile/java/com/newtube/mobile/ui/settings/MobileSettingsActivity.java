package com.newtube.mobile.ui.settings;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.transition.MaterialSharedAxis;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.browse.AccountsSheet;
import com.newtube.mobile.ui.common.MobileActivity;

/**
 * Phone Settings (issue #2: "way too convoluted - lots of options per section"). Until 1.14 the
 * Settings were SmartTube's TV categories drawn by the AppDialog renderer: every radio option
 * inline (Player alone ran to ~120 rows), TV-only knobs next to real ones, no current values.
 *
 * <p>Now a short tree in the shape of YouTube's and LibreTube's settings, defined in
 * {@link SettingsPages}: a top level with icons and a line about each section, pages of a dozen
 * rows at most, every choice one row showing its current value and opening a dialog, and the
 * power-user knobs under Advanced. The rows read and write the same prefs classes the TV
 * presenters did, so nothing a user set is lost.</p>
 *
 * <p>One page at a time, as a fragment with the shared-axis motion of Android's own Settings; the
 * fragment back stack is the page stack, so a theme switch (which recreates this screen) comes
 * back on the same page with the same pages behind it.</p>
 */
public class MobileSettingsActivity extends MobileActivity {
    /** Open straight on this page (e.g. Captions from the player), with the top level behind it. */
    public static final String EXTRA_PAGE = "newtube:settings_page";

    public static Intent intent(@NonNull Context context, @Nullable String pageId) {
        Intent intent = new Intent(context, MobileSettingsActivity.class);
        if (pageId != null) {
            intent.putExtra(EXTRA_PAGE, pageId);
        }
        return intent;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mobile_settings);

        registerBackHandler(this::handleBack);

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .setReorderingAllowed(true)
                    .add(R.id.settings_container, SettingsPageFragment.newInstance(SettingsPages.ROOT))
                    .commitNow();
            String page = getIntent().getStringExtra(EXTRA_PAGE);
            if (page != null && !SettingsPages.ROOT.equals(page)) {
                openPage(null, page, false, null);
            }
        }
    }

    /** Opens a page of the tree on top of {@code from}, the page whose row was tapped. */
    public void openPage(@NonNull Fragment from, @NonNull String pageId) {
        openPage(from, pageId, true, null);
    }

    /** The search page, on top of the top level like any other page. */
    void openSearch(@NonNull Fragment from) {
        push(from, new SettingsSearchFragment(), "search", true);
    }

    /**
     * A search result: the page it opens, or the page it is on with the row lit up. Either way it
     * goes on top of the search page, so Back comes back to the results.
     */
    void openResult(@NonNull Fragment from, @NonNull SettingsSearch.Entry entry) {
        if (entry.account) {
            AccountsSheet.show(this, () -> { }); // the account row's "page"
        } else if (entry.opens != null) {
            openPage(from, entry.opens, true, null);
        } else {
            openPage(from, entry.pageId, true, String.valueOf(entry.title));
        }
    }

    private void openPage(@Nullable Fragment from, @NonNull String pageId, boolean animate, @Nullable String highlight) {
        push(from, SettingsPageFragment.newInstance(pageId, highlight), pageId, animate);
    }

    /**
     * {@code from}: the page asking, or null. A second tap that lands before the first one's page
     * replaced it, or while that page slides in over it, finds {@code from} no longer on top and is
     * dropped: a double tap opens one page, not two.
     */
    private void push(@Nullable Fragment from, @NonNull Fragment next, @NonNull String name, boolean animate) {
        FragmentManager fragments = getSupportFragmentManager();
        if (fragments.isStateSaved()) {
            return;
        }
        fragments.executePendingTransactions();
        Fragment current = fragments.findFragmentById(R.id.settings_container);
        if (from != null && current != from) {
            return;
        }
        if (animate) {
            next.setEnterTransition(new MaterialSharedAxis(MaterialSharedAxis.X, true));
            next.setReturnTransition(new MaterialSharedAxis(MaterialSharedAxis.X, false));
            if (current != null) {
                current.setExitTransition(new MaterialSharedAxis(MaterialSharedAxis.X, true));
                current.setReenterTransition(new MaterialSharedAxis(MaterialSharedAxis.X, false));
            }
        }
        fragments.beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.settings_container, next)
                .addToBackStack(name)
                .commit();
    }

    private void handleBack() {
        FragmentManager fragments = getSupportFragmentManager();
        if (fragments.getBackStackEntryCount() > 0) {
            fragments.popBackStack();
        } else {
            finish();
        }
    }
}
