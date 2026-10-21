package com.newtube.mobile.ui.playback;

import android.app.Activity;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.util.Consumer;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Haptics;

import java.util.function.LongSupplier;

/**
 * VOIDXDEV(lyrics): the playing song's lyrics (found by {@link Lyrics}) in a panel under the video, like
 * YouTube Music's Lyrics tab. Timed lyrics light up line by line and keep the sung line in view;
 * plain ones are one selectable text, so any of it can be copied. Same frosted sheet and sizing as
 * the Description panel (DescriptionSheet), shown by the player (showPlayerSheet). The header names
 * the provider; a tap on it lists the others ({@link Chooser}).
 */
final class LyricsSheet {
    /** Light a line up this early, so it is there as it is sung rather than just after. */
    private static final long LEAD_MS = 300;
    /** After the listener scrolls, leave the lyrics where they put them this long. */
    private static final long HANDS_OFF_MS = 4_000;
    private static final long TICK_MS = 200;

    private LyricsSheet() {
    }

    /**
     * {@code position} is the player's position in ms (negative when unknown); {@code showSheet} shows
     * the provider chooser the way the player shows its sheets (showPlayerSheet).
     */
    static BottomSheetDialog create(@NonNull Activity activity, @NonNull String videoId, String title, String channel,
                                    @Nullable View video, @NonNull LongSupplier position,
                                    @NonNull Consumer<BottomSheetDialog> showSheet) {
        BottomSheetDialog dialog = Haptics.watch(new BottomSheetDialog(activity));
        View content = LayoutInflater.from(activity).inflate(R.layout.sheet_mobile_lyrics, null);
        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setDimAmount(0f); // the playing video above stays as it is, never dimmed
        }
        if (video != null) {
            DescriptionSheet.fillUnder(dialog, content, video);
            if (window != null) {
                leaveVideoFree(window, video);
            }
        } else {
            dialog.setCanceledOnTouchOutside(false); // fullscreen: a tap on the video keeps them too
        }
        content.findViewById(R.id.lyrics_sheet_close).setOnClickListener(v -> dialog.dismiss());
        Lyrics.fetch(videoId, title != null ? title : "", channel != null ? channel : "", song -> {
            content.findViewById(R.id.lyrics_sheet_loading).setVisibility(View.GONE);
            if (song == null) {
                content.findViewById(R.id.lyrics_sheet_empty).setVisibility(View.VISIBLE);
                return;
            }
            Runnable showLyrics = () -> show(dialog, content, song, position);
            Chooser chooser = new Chooser(activity, song, showSheet, showLyrics);
            View provider = content.findViewById(R.id.lyrics_sheet_provider);
            provider.setVisibility(View.VISIBLE);
            provider.setOnClickListener(v -> chooser.open());
            showLyrics.run();
        });
        return dialog;
    }

    /**
     * VOIDXDEV(lyrics): the minimize drag fades the lyrics out with the watch page, and back in if it is
     * let go, as it does the comments panel (MobilePlaybackActivity.applyMorph / resetMorph).
     */
    static void setMorphAlpha(@Nullable BottomSheetDialog sheet, float alpha) {
        Window window = sheet != null ? sheet.getWindow() : null;
        View decor = window != null ? window.peekDecorView() : null;
        if (decor != null) {
            decor.setAlpha(alpha);
        }
    }

    /**
     * The sheet's window ends at the video's bottom edge, and a touch outside it goes to the page
     * (FLAG_NOT_TOUCH_MODAL): the video takes taps as usual - controls, seek, fullscreen - and the
     * lyrics stay open. They close by the X, a drag down or Back.
     */
    private static void leaveVideoFree(Window window, View video) {
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        View decor = window.getDecorView();
        int[] at = new int[2];
        decor.getViewTreeObserver().addOnPreDrawListener(() -> {
            if (decor.getHeight() == 0) {
                return true;
            }
            decor.getLocationOnScreen(at);
            int bottom = at[1] + decor.getHeight();
            video.getLocationOnScreen(at);
            int height = bottom - (at[1] + video.getHeight());
            if (height <= 0 || height == decor.getHeight()) {
                return true; // in place (or the video fills the screen: nothing above to free)
            }
            WindowManager.LayoutParams params = window.getAttributes();
            params.gravity = Gravity.BOTTOM;
            params.height = height;
            window.setAttributes(params);
            return false;
        });
    }

    /** The lyrics of the provider the song {@link Lyrics.Song#shown shows}, in place of any shown before. */
    private static void show(BottomSheetDialog dialog, View content, Lyrics.Song song, LongSupplier position) {
        Lyrics lyrics = song.lyrics(song.shown); // never null: only a provider with lyrics is shown
        String provider = Lyrics.PROVIDERS[song.shown];
        ((TextView) content.findViewById(R.id.lyrics_sheet_provider_name)).setText(provider);
        content.findViewById(R.id.lyrics_sheet_provider).setContentDescription(
                content.getContext().getString(R.string.mobile_lyrics_provider_description, provider));
        NestedScrollView scroll = content.findViewById(R.id.lyrics_sheet_scroll);
        scroll.setVisibility(View.VISIBLE);
        scroll.scrollTo(0, 0);
        ((TextView) content.findViewById(R.id.lyrics_sheet_song)).setText(lyrics.title);
        ((TextView) content.findViewById(R.id.lyrics_sheet_artist)).setText(lyrics.artist);
        ((TextView) content.findViewById(R.id.lyrics_sheet_source))
                .setText(content.getContext().getString(R.string.mobile_lyrics_source, lyrics.source));
        LinearLayout lines = content.findViewById(R.id.lyrics_sheet_lines);
        lines.removeAllViews();
        lines.setTag(lyrics.times); // the follow() of lyrics shown before stops
        if (lyrics.times == null) {
            TextView text = line(lines, String.join("\n", lyrics.lines), 16, R.color.mobile_color_on_surface);
            text.setLineSpacing(0, 1.3f);
            text.setTextIsSelectable(true);
            lines.addView(text);
            return;
        }
        for (String words : lyrics.lines) {
            TextView line = line(lines, words, 22, R.color.mobile_color_on_surface_secondary);
            line.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            line.setPadding(0, 0, 0, Math.round(14 * content.getResources().getDisplayMetrics().density));
            lines.addView(line);
        }
        follow(dialog, scroll, lines, lyrics.times, position);
    }

    /**
     * Lights the line being sung and keeps it a third of the way down, until the sheet closes or other
     * lyrics take the lines' place.
     */
    private static void follow(BottomSheetDialog dialog, NestedScrollView scroll, LinearLayout lines, long[] times,
                               LongSupplier position) {
        int dim = ContextCompat.getColor(scroll.getContext(), R.color.mobile_color_on_surface_secondary);
        int lit = ContextCompat.getColor(scroll.getContext(), R.color.mobile_color_on_surface);
        long[] touchedAt = {-HANDS_OFF_MS};
        scroll.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() != MotionEvent.ACTION_CANCEL) {
                touchedAt[0] = SystemClock.uptimeMillis();
            }
            return false;
        });
        lines.post(new Runnable() {
            private int mCurrent = -1;

            @Override
            public void run() {
                if (!dialog.isShowing() || lines.getTag() != times) {
                    return;
                }
                long at = position.getAsLong() + LEAD_MS;
                int sung = -1;
                while (sung + 1 < times.length && times[sung + 1] <= at) {
                    sung++;
                }
                if (sung != mCurrent) {
                    if (mCurrent >= 0) {
                        ((TextView) lines.getChildAt(mCurrent)).setTextColor(dim);
                    }
                    mCurrent = sung;
                    if (sung >= 0) {
                        View line = lines.getChildAt(sung);
                        ((TextView) line).setTextColor(lit);
                        if (SystemClock.uptimeMillis() - touchedAt[0] > HANDS_OFF_MS) {
                            int y = lines.getTop() + line.getTop() - scroll.getHeight() / 3;
                            scroll.smoothScrollTo(0, Math.max(0, y));
                        }
                    }
                }
                lines.postDelayed(this, TICK_MS);
            }
        });
    }

    private static TextView line(View parent, CharSequence text, int sp, int color) {
        TextView view = new TextView(parent.getContext());
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(ContextCompat.getColor(parent.getContext(), color));
        return view;
    }

    /**
     * VOIDXDEV(lyrics): BitChord's provider chooser (LyricsProviderSheet), in the player's single-choice
     * sheet: every provider with what it has for this song - the current one, lyrics found (tap to
     * use), none, being fetched, or not asked yet (tap to fetch). A pick with lyrics in hand switches
     * at once and closes the list; one not asked yet is fetched, and switched to when it answers with
     * lyrics unless another pick came after it. Neither the current provider nor one that had none can
     * be picked. For this song only, as in BitChord: the next one starts from the automatic choice.
     */
    private static final class Chooser {
        private final Activity mActivity;
        private final Lyrics.Song mSong;
        private final Consumer<BottomSheetDialog> mShowSheet;
        /** Shows the lyrics of the provider the song shows. */
        private final Runnable mShowLyrics;
        /** Providers asked from here whose answer is still to come. */
        private final boolean[] mAsking = new boolean[Lyrics.PROVIDERS.length];
        /** The last pick still being fetched, -1 when none. */
        private int mWanted = -1;
        /** The open list and its rows, null while closed. */
        @Nullable
        private BottomSheetDialog mList;
        @Nullable
        private LinearLayout mRows;

        Chooser(Activity activity, Lyrics.Song song, Consumer<BottomSheetDialog> showSheet, Runnable showLyrics) {
            mActivity = activity;
            mSong = song;
            mShowSheet = showSheet;
            mShowLyrics = showLyrics;
        }

        void open() {
            if (mList != null) {
                return;
            }
            BottomSheetDialog list = Haptics.watch(new BottomSheetDialog(mActivity));
            View content = LayoutInflater.from(mActivity).inflate(R.layout.sheet_mobile_quality, null);
            list.setContentView(content);
            ((TextView) content.findViewById(R.id.quality_sheet_quality_title))
                    .setText(R.string.mobile_lyrics_provider_choose);
            mRows = content.findViewById(R.id.quality_sheet_quality_list);
            mList = list;
            list.setOnDismissListener(d -> {
                mList = null;
                mRows = null;
            });
            fill();
            mShowSheet.accept(list);
        }

        /** A row per provider: its name over what it has for this song. */
        private void fill() {
            if (mRows == null) {
                return;
            }
            mRows.removeAllViews();
            int secondary = ContextCompat.getColor(mActivity, R.color.mobile_color_on_surface_secondary);
            for (int i = 0; i < Lyrics.PROVIDERS.length; i++) {
                int provider = i;
                boolean current = i == mSong.shown;
                boolean none = mSong.asked(i) && mSong.lyrics(i) == null;
                int status = current ? R.string.mobile_lyrics_provider_current
                        : none ? R.string.mobile_lyrics_provider_not_found
                        : mSong.asked(i) ? R.string.mobile_lyrics_provider_found
                        : mAsking[i] ? R.string.mobile_lyrics_provider_fetching
                        : R.string.mobile_lyrics_provider_not_fetched;
                SpannableStringBuilder label = new SpannableStringBuilder(Lyrics.PROVIDERS[i]).append('\n');
                int start = label.length();
                label.append(mActivity.getString(status));
                label.setSpan(new RelativeSizeSpan(0.87f), start, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                label.setSpan(new ForegroundColorSpan(secondary), start, label.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                View row = LayoutInflater.from(mActivity).inflate(R.layout.item_mobile_quality_row, mRows, false);
                ((TextView) row.findViewById(R.id.quality_row_label)).setText(label);
                row.findViewById(R.id.quality_row_check).setVisibility(current ? View.VISIBLE : View.INVISIBLE);
                row.setOnClickListener(v -> pick(provider));
                row.setEnabled(!current && !none);
                row.setAlpha(none ? 0.5f : 1f);
                mRows.addView(row);
            }
        }

        private void pick(int provider) {
            if (mSong.asked(provider)) { // lyrics in hand (a provider that had none can't be picked)
                switchTo(provider);
                return;
            }
            mWanted = provider;
            if (!mAsking[provider]) {
                mAsking[provider] = true;
                Lyrics.fetch(mSong, provider, lyrics -> {
                    mAsking[provider] = false;
                    if (lyrics != null && mWanted == provider) {
                        switchTo(provider);
                    } else {
                        fill(); // the row says what came back
                    }
                });
            }
            fill();
        }

        private void switchTo(int provider) {
            mWanted = -1;
            mSong.show(provider);
            mShowLyrics.run();
            if (mList != null) {
                mList.dismiss();
            }
        }
    }
}
