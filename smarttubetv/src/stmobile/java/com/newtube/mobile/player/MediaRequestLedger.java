package com.newtube.mobile.player;

import androidx.annotation.Nullable;
import androidx.media3.datasource.HttpDataSource;

/**
 * NEWTUBE(wall-memory): what one open's media requests looked like, for the one-minute wall's
 * signature (MediaServiceCore's PlaybackWallMemory.classify): the stream start of the last request
 * googlevideo refused with 403, and the lowest and highest start of those it served. The wall is on
 * stream position, not on elapsed time (r11: a jump to 73.7 s on a walled visitor was refused at the
 * first request past 60 s), so start times are what count, not the position or buffer at the error.
 * Reset by every open (a recovery reload is a new open). Media3's playback thread writes, the error
 * path reads: every access holds the lock, so a reset never interleaves with an update.
 */
public final class MediaRequestLedger {
    private long mForbiddenStartMs = -1;
    private long mLowestServedStartMs = -1;
    private long mHighestServedStartMs = -1;

    public synchronized void reset() {
        mForbiddenStartMs = -1;
        mLowestServedStartMs = -1;
        mHighestServedStartMs = -1;
    }

    /** A media request starting at {@code startMs} delivered bytes. */
    public synchronized void onServed(long startMs) {
        if (startMs < 0) {
            return;
        }
        if (mLowestServedStartMs < 0 || startMs < mLowestServedStartMs) {
            mLowestServedStartMs = startMs;
        }
        if (startMs > mHighestServedStartMs) {
            mHighestServedStartMs = startMs;
        }
    }

    /** A media request starting at {@code startMs} was refused with HTTP 403. */
    public synchronized void onForbidden(long startMs) {
        if (startMs >= 0) {
            mForbiddenStartMs = startMs;
        }
    }

    public synchronized long forbiddenStartMs() {
        return mForbiddenStartMs;
    }

    public synchronized long lowestServedStartMs() {
        return mLowestServedStartMs;
    }

    public synchronized long highestServedStartMs() {
        return mHighestServedStartMs;
    }

    /** An HTTP 403 anywhere in the load error's cause chain. */
    public static boolean isForbidden(@Nullable Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpDataSource.InvalidResponseCodeException
                    && ((HttpDataSource.InvalidResponseCodeException) cause).responseCode == 403) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
