package com.newtube.mobile.ui.settings;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.Keyframe;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Haptics;
import com.newtube.mobile.ui.common.MobileSnackbar;

import java.util.List;

/**
 * One page of the phone Settings tree: a top bar with the page's title and a list of
 * {@link SettingsRow}s from {@link SettingsPages}. The rows are built again whenever the page comes
 * back to the front (a sheet or another page may have changed a value), and their values are read
 * again after every change on the page.
 */
public final class SettingsPageFragment extends Fragment implements SettingsAdapter.Listener {
    private static final String ARG_PAGE = "page";
    /** A search result opened this page: scroll to this row and make it glow once. */
    private static final String ARG_HIGHLIGHT = "highlight";
    /** After the page's slide-in, so the glow is seen. */
    private static final long HIGHLIGHT_DELAY_MS = 350;

    private String mPageId;
    private SettingsAdapter mAdapter;
    private RecyclerView mList;
    private TextView mTitle;
    @Nullable private Dialog mDialog;
    @Nullable private String mHighlight;

    public static SettingsPageFragment newInstance(@NonNull String pageId) {
        return newInstance(pageId, null);
    }

    public static SettingsPageFragment newInstance(@NonNull String pageId, @Nullable String highlight) {
        SettingsPageFragment fragment = new SettingsPageFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PAGE, pageId);
        if (highlight != null) {
            args.putString(ARG_HIGHLIGHT, highlight);
        }
        fragment.setArguments(args);
        return fragment;
    }

    @NonNull
    public String getPageId() {
        return mPageId;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPageId = requireArguments().getString(ARG_PAGE, SettingsPages.ROOT);
        mHighlight = requireArguments().getString(ARG_HIGHLIGHT);
        // Once: coming back to this page later (or after a recreation) shouldn't glow again.
        requireArguments().remove(ARG_HIGHLIGHT);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mobile_settings_page, container, false);
        mTitle = view.findViewById(R.id.settings_title);
        mList = view.findViewById(R.id.settings_list);
        view.findViewById(R.id.settings_back).setOnClickListener(
                v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());

        mAdapter = new SettingsAdapter(this);
        mList.setLayoutManager(new LinearLayoutManager(requireContext()));
        mList.setAdapter(mAdapter);
        // Rows are rebound in place when a value changes: no cross-fade on every tap.
        mList.setItemAnimator(null);
        // The rows go in before the first layout: the list's saved position (coming back from a
        // page opened over this one, or after a recreation) is dropped by a layout with no items.
        rebuild();
        if (mHighlight != null) {
            int index = mAdapter.indexOf(mHighlight);
            if (index > 2) {
                // Before the first layout, like the rows: the row lands a third of the way down.
                ((LinearLayoutManager) mList.getLayoutManager()).scrollToPositionWithOffset(index,
                        getResources().getDisplayMetrics().heightPixels / 4);
            }
        }
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (SettingsPages.ROOT.equals(mPageId)) {
            View search = view.findViewById(R.id.settings_search_field);
            search.setVisibility(View.VISIBLE);
            // VOIDXDEV(glass): frosted over the page; the rows start below it and scroll under it.
            com.newtube.mobile.ui.common.Glass.blur(search, view.findViewById(R.id.settings_blur_target));
            ((ViewGroup.MarginLayoutParams) search.getLayoutParams()).topMargin += Math.round(4 * getResources().getDisplayMetrics().density);
            mList.setPadding(mList.getPaddingLeft(),
                    getResources().getDimensionPixelSize(R.dimen.mobile_settings_search_room),
                    mList.getPaddingRight(), mList.getPaddingBottom());
            search.setOnClickListener(v -> {
                if (getActivity() instanceof MobileSettingsActivity) {
                    ((MobileSettingsActivity) getActivity()).openSearch(this);
                }
            });
        }
        if (mHighlight != null) {
            String title = mHighlight;
            mHighlight = null;
            mList.postDelayed(() -> glow(title), HIGHLIGHT_DELAY_MS);
        }
    }

    /** Android Settings' way of showing where a search result is: the row lights up, then fades. */
    private void glow(@NonNull String title) {
        if (getContext() == null || mList == null) {
            return;
        }
        RecyclerView.ViewHolder holder = mList.findViewHolderForAdapterPosition(mAdapter.indexOf(title));
        if (holder == null) {
            return;
        }
        View row = holder.itemView;
        ColorDrawable light = new ColorDrawable(ContextCompat.getColor(requireContext(), R.color.mobile_settings_search_highlight));
        light.setAlpha(0);
        row.setForeground(light);
        ObjectAnimator animator = ObjectAnimator.ofPropertyValuesHolder(light, PropertyValuesHolder.ofKeyframe("alpha",
                Keyframe.ofInt(0f, 0), Keyframe.ofInt(0.15f, 255), Keyframe.ofInt(0.6f, 255), Keyframe.ofInt(1f, 0)));
        animator.setDuration(1600);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (row.getForeground() == light) {
                    row.setForeground(null);
                }
            }
        });
        animator.start();
    }

    @Override
    public void onResume() {
        super.onResume();
        // A sheet or another page may have changed a value; same rows, so they rebind in place.
        rebuild();
    }

    @Override
    public void onDestroyView() {
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }
        super.onDestroyView();
    }

    /** Builds the page again: its rows, and which of them exist, may depend on other values. */
    public void rebuild() {
        if (getContext() == null || mAdapter == null) {
            return;
        }
        SettingsPages.Page page = SettingsPages.build(requireContext(), mPageId);
        mTitle.setText(page.title);
        mAdapter.submit(page.rows);
    }

    /** Opens another page of the tree on top of this one. */
    public void openPage(@NonNull String pageId) {
        if (getActivity() instanceof MobileSettingsActivity) {
            ((MobileSettingsActivity) getActivity()).openPage(this, pageId);
        }
    }

    @Override
    public void onRowClicked(@NonNull SettingsRow row) {
        switch (row.kind) {
            case SettingsRow.KIND_LINK:
            case SettingsRow.KIND_FOOTER:
                if (row.page != null) {
                    openPage(row.page);
                } else if (row.action != null) {
                    row.action.run(this);
                }
                break;
            case SettingsRow.KIND_SWITCH:
                if (row.checked != null && row.toggle != null) {
                    row.toggle.set(!row.checked.getAsBoolean());
                    applied(row);
                }
                break;
            case SettingsRow.KIND_CHOICE:
                showChoiceDialog(row);
                break;
            default:
                break;
        }
    }

    private void applied(SettingsRow row) {
        rebuild();
        if (row.restart) {
            offerRestart();
        }
    }

    /** A change that lands after a restart says so, and offers to do it now. */
    public void offerRestart() {
        // The snackbar outlives this page (it sits on the activity): its action can't ask the
        // fragment for a context after Back took the page away.
        Context app = requireContext().getApplicationContext();
        MobileSnackbar.show(requireContext(), getString(R.string.mobile_settings_restart_needed),
                getString(R.string.mobile_settings_restart), () -> Utils.restartTheApp(app));
    }

    /**
     * The choice sheet (VOIDXDEV: was a centred dialog): the options as a radio list, the current one
     * marked, in the app's frosted bottom sheet; a tap applies that option and closes the sheet.
     */
    private void showChoiceDialog(SettingsRow row) {
        if (row.options == null || row.pick == null) {
            return;
        }
        List<SettingsRow.Option> options = row.options;
        int current = row.currentIndex();

        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        View content = inflater.inflate(R.layout.sheet_mobile_speed, null);
        ((TextView) content.findViewById(R.id.speed_sheet_title)).setText(row.title);
        content.findViewById(R.id.speed_sheet_more).setVisibility(View.GONE);
        ViewGroup list = content.findViewById(R.id.speed_sheet_list);
        for (int i = 0; i < options.size(); i++) {
            SettingsRow.Option option = options.get(i);
            View view = inflater.inflate(R.layout.item_mobile_settings_choice, list, false);
            ((RadioButton) view.findViewById(R.id.settings_choice_radio)).setChecked(i == current);
            ((TextView) view.findViewById(R.id.settings_choice_label)).setText(option.label);
            TextView description = view.findViewById(R.id.settings_choice_description);
            description.setText(option.description);
            description.setVisibility(option.description != null ? View.VISIBLE : View.GONE);
            ImageView icon = view.findViewById(R.id.settings_choice_icon);
            icon.setImageResource(option.icon);
            icon.setVisibility(option.icon != 0 ? View.VISIBLE : View.GONE);
            int which = i;
            view.setOnClickListener(v -> {
                sheet.dismiss();
                if (which != current) {
                    row.pick.pick(option.value);
                    applied(row);
                }
            });
            list.addView(view);
        }
        sheet.setContentView(content);
        // A long list (UI scale, quality) opens whole rather than half-peeked.
        sheet.getBehavior().setSkipCollapsed(true);
        sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        showDialog(sheet);
        sheet.show();
    }

    /** For rows whose action opens their own dialog. */
    public void showDialog(@NonNull Dialog dialog) {
        if (mDialog != null && mDialog.isShowing()) {
            mDialog.dismiss();
        }
        mDialog = Haptics.watch(dialog);
    }
}
