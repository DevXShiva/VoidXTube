package com.newtube.mobile.player;

import android.net.Uri;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NEWTUBE(readiness): when googlevideo holds back the media of an answer that announced pre-roll
 * ads, wait until the answer is ready instead of taking the refusal as a dead route.
 *
 * <p>For some clients googlevideo refuses the content media of an answer with pre-roll ads for
 * roughly the ads' skippable time after /player answered (MediaItemFormatInfo#getMediaReadyAtMs,
 * computed with yt-dlp's rule in PrerollAds). On a Pixel 9 (2026-09-28) the app asked 0.3 s after
 * /player, got HTTP 403, re-resolved - which fetched a new answer with a new wait - and gave up
 * after four tries, on a video that played when the same URLs were reused a minute later. Not every
 * client is held back. In the netbench answers of 2026-09-28, MWEB and WEB_EMBED media was refused
 * right after /player (in 73 of 77 answers with pre-roll ads) and never once the announced time had
 * passed (MWEB was served 0.3-3.3 s before it), while WEB, WEB_SAFARI and WEB_MUSIC answers
 * announced the same ads and their media was served at once. So the gate asks first and waits only
 * when refused: an answer that is not held back plays with no delay, one that is gets the full
 * announced wait. The app never tries to shorten the wait.
 *
 * <p>One gate per answer. It sits on the HTTP side of the media cache (Media3SourceFactory), so
 * only a network response counts as "served"; a cache hit proves nothing about the answer. Until
 * the answer has been served, a 403 before {@code readyAt} waits until {@code readyAt}, and a 403
 * after it is retried on the same URL after 3 and 6 s (the estimate may be short), never past
 * {@code readyAt + RETRY_WINDOW_MS}. After that - or once anything was served - a 403 surfaces as
 * before and ErrorFixerController's route recovery takes over. The waits run on media3's loader
 * thread; a cancelled load interrupts them. Every route that plays an answer's own media takes the
 * gate: generated DASH (also the DASH half of a merged source), the answer's progressive formats and
 * its HLS manifest when the answer is VOD (VodDelivery). Only media ({@code /videoplayback}) counts
 * as served: an HLS playlist from manifest.googlevideo.com says nothing about the segments.
 */
@UnstableApi
public final class ReadinessGate {
    static final long RETRY_WINDOW_MS = 10_000;
    static final long[] RETRY_BACKOFF_MS = {3_000, 6_000};

    /** Time and waiting, injectable for tests. */
    interface Clock {
        long now();

        void sleep(long ms) throws InterruptedIOException;
    }

    static final Clock REAL_CLOCK = new Clock() {
        @Override public long now() {
            return SystemClock.elapsedRealtime();
        }

        @Override public void sleep(long ms) throws InterruptedIOException {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("readiness wait cancelled");
            }
        }
    };

    private static volatile boolean sEnabled = true;
    // Keyed by identity (format infos do not override equals): a rebuilt source for the same
    // answer - an engine restart, the stashed next-video source - shares its wait and its verdict.
    private static final Map<MediaItemFormatInfo, ReadinessGate> sGates = new WeakHashMap<>();

    final long readyAtMs;
    final long prerollMs;
    private final Clock mClock;
    private final AtomicBoolean mServed = new AtomicBoolean();
    /** When the first network response of this answer was served (valid once mServed). */
    private volatile long mServedAtMs;
    private final AtomicBoolean mWaitLogged = new AtomicBoolean();
    private volatile boolean mRefused;

    ReadinessGate(long readyAtMs, long prerollMs, Clock clock) {
        this.readyAtMs = readyAtMs;
        this.prerollMs = prerollMs;
        mClock = clock;
    }

    /** Rollback switch (MobileMainApplication, debug.arc.readiness=0). */
    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    /** This answer's gate, or null when it announced no pre-roll wait (the usual case). */
    @Nullable
    static ReadinessGate forAnswer(@Nullable MediaItemFormatInfo formatInfo) {
        if (!sEnabled || formatInfo == null || formatInfo.getMediaReadyAtMs() <= 0) {
            return null;
        }
        synchronized (sGates) {
            ReadinessGate gate = sGates.get(formatInfo);
            if (gate == null) {
                gate = new ReadinessGate(formatInfo.getMediaReadyAtMs(), formatInfo.getPrerollWaitMs(),
                        REAL_CLOCK);
                sGates.put(formatInfo, gate);
            }
            return gate;
        }
    }

    /**
     * How much longer this answer's media may legitimately be held back, ms: until it has been
     * served, the rest of its wait plus the retry window; 0 once served, or without a gate. The
     * buffering watchdog waits this out before it calls a spinner a stall.
     */
    static long holdLeftMs(@Nullable MediaItemFormatInfo formatInfo) {
        ReadinessGate gate;
        synchronized (sGates) {
            gate = formatInfo != null ? sGates.get(formatInfo) : null;
        }
        return gate != null ? gate.holdLeftMs() : 0;
    }

    /** Until this answer's announced ready time, ms; 0 without an announced wait. */
    static long untilReadyMs(@Nullable MediaItemFormatInfo formatInfo) {
        if (!sEnabled || formatInfo == null || formatInfo.getMediaReadyAtMs() <= 0) {
            return 0;
        }
        return Math.max(0, formatInfo.getMediaReadyAtMs() - REAL_CLOCK.now());
    }

    long holdLeftMs() {
        return mServed.get() ? 0 : Math.max(0, readyAtMs + RETRY_WINDOW_MS - mClock.now());
    }

    DataSource.Factory wrap(DataSource.Factory upstream) {
        return () -> new Gated(upstream.createDataSource());
    }

    private final class Gated implements DataSource {
        private final DataSource mUpstream;

        Gated(DataSource upstream) {
            mUpstream = upstream;
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
            mUpstream.addTransferListener(transferListener);
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            int retries = 0;
            while (true) {
                long startedAtMs = mClock.now();
                try {
                    long length = mUpstream.open(dataSpec);
                    if (!isMedia(dataSpec.uri)) {
                        return length;
                    }
                    boolean first;
                    synchronized (mServed) {
                        // The first success sets the time; a later one must not move it forward.
                        first = !mServed.get();
                        if (first) {
                            mServedAtMs = mClock.now();
                            mServed.set(true);
                        }
                    }
                    if (first && mRefused) {
                        NetPath.log(NetPath.context() + " readiness-served sinceReadyMs="
                                + (mClock.now() - readyAtMs) + " prerollMs=" + prerollMs);
                    }
                    return length;
                } catch (IOException e) {
                    // A refusal of a request sent after the answer was served is real. One sent before
                    // (audio and video retried together at readyAt) is still part of the wait.
                    if (responseCode(e) != 403 || mServed.get() && startedAtMs > mServedAtMs) {
                        throw e;
                    }
                    mRefused = true;
                    long now = mClock.now();
                    long sleep;
                    if (now < readyAtMs) {
                        // Held back, as announced: wait the announced time out.
                        sleep = readyAtMs - now;
                        if (mWaitLogged.compareAndSet(false, true)) {
                            // media=n: an HLS playlist was refused (is it held back too? phase 5).
                            NetPath.log(NetPath.context() + " readiness-wait ms=" + sleep
                                    + " prerollMs=" + prerollMs
                                    + " media=" + (isMedia(dataSpec.uri) ? "y" : "n"));
                        }
                    } else {
                        sleep = retries < RETRY_BACKOFF_MS.length ? RETRY_BACKOFF_MS[retries] : -1;
                        if (sleep < 0 || now + sleep > readyAtMs + RETRY_WINDOW_MS) {
                            throw e;
                        }
                        retries++;
                        NetPath.log(NetPath.context() + " readiness-retry code=403 attempt=" + retries
                                + " inMs=" + sleep + " sinceReadyMs=" + (now - readyAtMs)
                                + " media=" + (isMedia(dataSpec.uri) ? "y" : "n"));
                    }
                    try {
                        mUpstream.close();
                    } catch (IOException ignored) {
                        // The failed open's own resources; nothing to report.
                    }
                    mClock.sleep(sleep);
                }
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return mUpstream.read(buffer, offset, length);
        }

        @Nullable
        @Override
        public Uri getUri() {
            return mUpstream.getUri();
        }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return mUpstream.getResponseHeaders();
        }

        @Override
        public void close() throws IOException {
            mUpstream.close();
        }
    }

    /** Media, as opposed to a playlist or manifest: googlevideo's /videoplayback, any host. */
    static boolean isMedia(@Nullable Uri uri) {
        String path = uri != null ? uri.getPath() : null;
        return path != null && path.startsWith("/videoplayback");
    }

    /** The HTTP status in this failure's cause chain, or -1. */
    static int responseCode(@Nullable Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof HttpDataSource.InvalidResponseCodeException) {
                return ((HttpDataSource.InvalidResponseCodeException) t).responseCode;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return -1;
    }
}
