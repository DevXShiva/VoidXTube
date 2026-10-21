package com.newtube.mobile.ui.browse;

import android.app.Activity;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Motion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * VOIDXDEV(quotes): a tap on the brand pill shows a quote on a glass card for three seconds (a tap on
 * the card closes it sooner). The quotes come in a shuffled order that is saved, so none comes
 * back until every one has been shown; then a new order starts.
 */
final class QuoteCard {
    private static final long SHOW_MS = 3000;
    private static final String PREFS = "voidx_app";
    private static final String KEY_SEED = "quote_seed";
    private static final String KEY_POS = "quote_pos";

    private final View mCard;
    private final TextView mText;
    private final TextView mAuthor;
    private final String[] mQuotes;
    private final SharedPreferences mPrefs;
    private final Runnable mHide = this::hide;

    QuoteCard(Activity activity) {
        mCard = activity.findViewById(R.id.mobile_quote_card);
        mText = activity.findViewById(R.id.mobile_quote_text);
        mAuthor = activity.findViewById(R.id.mobile_quote_author);
        mQuotes = activity.getResources().getStringArray(R.array.voidx_quotes);
        mPrefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        mCard.setOnClickListener(v -> hide());
    }

    void show() {
        String[] quote = next().split("\\|", 2);
        mText.setText(quote[0]);
        mAuthor.setText(quote.length > 1 ? "— " + quote[1] : null);

        mCard.removeCallbacks(mHide);
        mCard.animate().cancel();
        mCard.setVisibility(View.VISIBLE);
        mCard.setAlpha(0f);
        mCard.setScaleX(0.9f);
        mCard.setScaleY(0.9f);
        mCard.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.ENTER_MS)
                .setInterpolator(Motion.EMPHASIZED_DECELERATE).start();
        mCard.postDelayed(mHide, SHOW_MS);
    }

    private void hide() {
        mCard.removeCallbacks(mHide);
        if (mCard.getVisibility() != View.VISIBLE) {
            return;
        }
        mCard.animate().cancel();
        mCard.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(Motion.EXIT_MS)
                .setInterpolator(Motion.EMPHASIZED_ACCELERATE)
                .withEndAction(() -> mCard.setVisibility(View.GONE)).start();
    }

    /**
     * The next quote of the saved shuffle. ponytail: the order is rebuilt from its seed on each tap
     * (a couple of hundred items) - and the last quote of one round may open the next, 1 in N.
     */
    private String next() {
        long seed = mPrefs.getLong(KEY_SEED, 0);
        int pos = mPrefs.getInt(KEY_POS, 0);
        if (seed == 0 || pos >= mQuotes.length) {
            seed = new Random().nextLong() | 1;
            pos = 0;
        }
        mPrefs.edit().putLong(KEY_SEED, seed).putInt(KEY_POS, pos + 1).apply();
        return mQuotes[order(mQuotes.length, seed).get(pos)];
    }

    static List<Integer> order(int size, long seed) {
        List<Integer> order = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            order.add(i);
        }
        Collections.shuffle(order, new Random(seed));
        return order;
    }
}
