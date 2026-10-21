package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertEquals;

import android.view.View;

import org.junit.Test;

/** VOIDXDEV(bidi): a comment's direction comes from its first letter, never from an invisible mark. */
public class CommentDirectionTest {
    @Test
    public void rtlMarkBeforeBareTimestampFollowsAppLanguage() {
        assertEquals(View.TEXT_DIRECTION_LOCALE, CommentsAdapter.textDirection("‏0:16 🎉"));
        assertEquals(View.TEXT_DIRECTION_LOCALE, CommentsAdapter.textDirection("4:05"));
    }

    @Test
    public void arabicTextReadsRightToLeft() {
        assertEquals(View.TEXT_DIRECTION_RTL, CommentsAdapter.textDirection("‏شكرا 0:16"));
    }

    @Test
    public void latinTextReadsLeftToRight() {
        assertEquals(View.TEXT_DIRECTION_LTR, CommentsAdapter.textDirection("‏Not me searching"));
    }

    @Test
    public void emptyFollowsAppLanguage() {
        assertEquals(View.TEXT_DIRECTION_LOCALE, CommentsAdapter.textDirection(null));
        assertEquals(View.TEXT_DIRECTION_LOCALE, CommentsAdapter.textDirection(""));
    }
}
