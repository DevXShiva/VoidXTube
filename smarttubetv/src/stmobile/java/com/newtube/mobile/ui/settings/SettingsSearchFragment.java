package com.newtube.mobile.ui.settings;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Search across the phone Settings, like Android's own: the top level's "Search settings" bar opens
 * this page (the same slide as any other page), with the field on top and the keyboard up. Results
 * update as you type, and a result opens its page with the row lit up. Back from that page comes
 * back here with the same query and results; Back from here returns to the top level.
 */
public final class SettingsSearchFragment extends Fragment {
    private static final String STATE_QUERY = "query";
    private static final String STATE_KEYBOARD_SHOWN = "keyboard_shown";

    private EditText mInput;
    private View mClear;
    private RecyclerView mResults;
    private TextView mEmpty;
    private ResultsAdapter mAdapter;
    @Nullable private SettingsSearch mSearch;
    private String mQuery = "";
    /** The keyboard comes up when the page opens, not again when coming back from a result. */
    private boolean mKeyboardShown;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            mQuery = savedInstanceState.getString(STATE_QUERY, "");
            mKeyboardShown = savedInstanceState.getBoolean(STATE_KEYBOARD_SHOWN);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mobile_settings_search, container, false);
        mInput = view.findViewById(R.id.settings_search_input);
        mClear = view.findViewById(R.id.settings_search_clear);
        mResults = view.findViewById(R.id.settings_search_results);
        mEmpty = view.findViewById(R.id.settings_search_empty);
        com.newtube.mobile.ui.common.Glass.blur(view.findViewById(R.id.settings_search_glass),
                view.findViewById(R.id.settings_search_blur_target)); // VOIDXDEV(glass)

        view.findViewById(R.id.settings_search_back).setOnClickListener(v -> {
            hideKeyboard();
            requireActivity().getOnBackPressedDispatcher().onBackPressed();
        });
        mClear.setOnClickListener(v -> {
            mInput.setText("");
            mInput.requestFocus();
            showKeyboard();
        });

        mAdapter = new ResultsAdapter(this::open);
        // After a recreation the results come with the index, a moment later: a layout with no
        // rows would throw the saved scroll position away.
        mAdapter.setStateRestorationPolicy(RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY);
        mResults.setLayoutManager(new LinearLayoutManager(requireContext()));
        mResults.setAdapter(mAdapter);
        mResults.setItemAnimator(null);
        mResults.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    hideKeyboard(); // reading the results, not typing
                }
            }
        });
        // Edge to edge, the window doesn't shrink for the keyboard: keep the last results above it.
        int basePadding = mResults.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(mResults, (list, insets) -> {
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            list.setPadding(list.getPaddingLeft(), list.getPaddingTop(), list.getPaddingRight(),
                    basePadding + Math.max(0, ime.bottom - bars.bottom));
            return insets;
        });

        // Built each time the page is shown (the rows, and which exist, follow the current settings),
        // off the main thread; what was typed meanwhile is searched as soon as it lands.
        SettingsSearch.buildAsync(requireContext(), search -> {
            if (getView() == null) {
                return; // the page was left before the index was ready
            }
            mSearch = search;
            update(false);
        });

        mInput.setText(mQuery);
        mInput.setSelection(mInput.length());
        mInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (s.toString().equals(mQuery)) {
                    return; // the field restoring its text (Back from a result), not typing
                }
                mQuery = s.toString();
                update(true);
            }
        });
        mInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard(); // the results are already there
                return true;
            }
            return false;
        });

        update(false);
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ViewCompat.requestApplyInsets(mResults);
        if (!mKeyboardShown) {
            mKeyboardShown = true;
            mInput.requestFocus();
            showKeyboard();
        }
    }

    @Override
    public void onPause() {
        hideKeyboard(); // leaving for a result or going back: never carry the keyboard along
        super.onPause();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_QUERY, mQuery);
        outState.putBoolean(STATE_KEYBOARD_SHOWN, mKeyboardShown);
    }

    private void update(boolean typed) {
        if (mSearch == null) {
            return;
        }
        List<SettingsSearch.Entry> hits = mSearch.find(mQuery);
        mAdapter.submit(hits, mQuery);
        if (typed) {
            mResults.scrollToPosition(0);
        }
        String trimmed = mQuery.trim();
        mClear.setVisibility(mQuery.isEmpty() ? View.GONE : View.VISIBLE);
        mEmpty.setVisibility(!trimmed.isEmpty() && hits.isEmpty() ? View.VISIBLE : View.GONE);
        if (!trimmed.isEmpty()) {
            mEmpty.setText(getString(R.string.mobile_settings_search_none, trimmed));
        }
    }

    private void open(@NonNull SettingsSearch.Entry entry) {
        hideKeyboard();
        if (getActivity() instanceof MobileSettingsActivity) {
            ((MobileSettingsActivity) getActivity()).openResult(this, entry);
        }
    }

    private void showKeyboard() {
        if (getActivity() == null) {
            return;
        }
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getActivity().getWindow(), mInput);
        mInput.post(() -> controller.show(WindowInsetsCompat.Type.ime()));
    }

    private void hideKeyboard() {
        if (getActivity() == null || mInput == null) {
            return;
        }
        WindowCompat.getInsetsController(getActivity().getWindow(), mInput).hide(WindowInsetsCompat.Type.ime());
    }

    /** The results: section icon, title with the matched words in bold, where the row is. */
    private static final class ResultsAdapter extends RecyclerView.Adapter<ResultsAdapter.Holder> {
        interface OnOpen {
            void open(@NonNull SettingsSearch.Entry entry);
        }

        private final OnOpen mOnOpen;
        private List<SettingsSearch.Entry> mEntries = new ArrayList<>();
        private String mQuery = "";

        ResultsAdapter(OnOpen onOpen) {
            mOnOpen = onOpen;
        }

        void submit(List<SettingsSearch.Entry> entries, String query) {
            mEntries = entries;
            mQuery = query;
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return mEntries.size();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_mobile_settings_search_result, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            SettingsSearch.Entry entry = mEntries.get(position);
            if (entry.icon != 0) {
                holder.icon.setImageResource(entry.icon);
                holder.icon.setVisibility(View.VISIBLE);
            } else {
                holder.icon.setImageDrawable(null);
                holder.icon.setVisibility(View.INVISIBLE); // keep the titles in one column
            }
            holder.title.setText(SettingsSearch.highlight(entry.title, mQuery));
            holder.path.setText(entry.path);
            holder.itemView.setOnClickListener(v -> mOnOpen.open(entry));
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView title;
            final TextView path;

            Holder(@NonNull View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.settings_result_icon);
                title = itemView.findViewById(R.id.settings_result_title);
                path = itemView.findViewById(R.id.settings_result_path);
            }
        }
    }
}
