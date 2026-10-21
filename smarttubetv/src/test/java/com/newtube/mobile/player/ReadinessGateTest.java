package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import android.app.Application;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** The pre-roll readiness gate against a fake clock and a googlevideo that refuses until a time. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ReadinessGateTest {
    private static final int LENGTH = 741;
    private static final DataSpec VIDEO = chunk("248");
    private static final DataSpec AUDIO = chunk("251");

    @Rule
    public TemporaryFolder mTemp = new TemporaryFolder();

    private static DataSpec chunk(String itag) {
        return new DataSpec.Builder()
                .setUri("https://rr1---sn-x.googlevideo.com/videoplayback?itag=" + itag + "&sig=x")
                .setPosition(0).setLength(LENGTH).build();
    }

    /** now() advances only through sleep(). */
    private static final class FakeClock implements ReadinessGate.Clock {
        long now;
        final List<Long> sleeps = new ArrayList<>();

        FakeClock(long now) {
            this.now = now;
        }

        @Override public long now() {
            return now;
        }

        @Override public void sleep(long ms) {
            sleeps.add(ms);
            now += ms;
        }
    }

    /** googlevideo that answers 403 to every open before {@code servesFrom}, then LENGTH bytes. */
    private static class Googlevideo implements DataSource {
        final FakeClock clock;
        final long servesFrom;
        final List<Long> openedAt = new ArrayList<>();
        private long mLeft;
        private Uri mUri;

        Googlevideo(FakeClock clock, long servesFrom) {
            this.clock = clock;
            this.servesFrom = servesFrom;
        }

        @Override public void addTransferListener(TransferListener transferListener) {
        }

        @Override public long open(DataSpec dataSpec) throws IOException {
            openedAt.add(clock.now);
            if (clock.now < servesFrom) {
                throw new HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null,
                        Collections.emptyMap(), dataSpec, new byte[0]);
            }
            mUri = dataSpec.uri;
            mLeft = LENGTH - dataSpec.position;
            return mLeft;
        }

        @Override public int read(byte[] buffer, int offset, int length) {
            if (mLeft == 0) {
                return -1;
            }
            int n = (int) Math.min(length, mLeft);
            mLeft -= n;
            return n;
        }

        @Nullable @Override public Uri getUri() {
            return mUri;
        }

        @Override public Map<String, List<String>> getResponseHeaders() {
            return Collections.emptyMap();
        }

        @Override public void close() {
            mUri = null;
        }
    }

    @Test
    public void anAnswerThatIsNotHeldBackPlaysWithoutWaiting() throws IOException {
        // WEB, WEB_SAFARI and WEB_MUSIC announce pre-roll ads and are served at once.
        FakeClock clock = new FakeClock(1_000);
        Googlevideo gv = new Googlevideo(clock, 0);
        ReadinessGate gate = new ReadinessGate(6_000, 5_000, clock);

        assertEquals(LENGTH, gate.wrap(() -> gv).createDataSource().open(VIDEO));
        assertEquals(Collections.emptyList(), clock.sleeps);
        assertEquals(0, gate.holdLeftMs());
    }

    @Test
    public void aHeldBackAnswerWaitsUntilItIsReady() throws IOException {
        FakeClock clock = new FakeClock(1_000);
        Googlevideo gv = new Googlevideo(clock, 6_000);
        ReadinessGate gate = new ReadinessGate(6_000, 5_000, clock);

        assertEquals(LENGTH, gate.wrap(() -> gv).createDataSource().open(VIDEO));
        assertEquals(Collections.singletonList(5_000L), clock.sleeps);
        assertEquals(Arrays.asList(1_000L, 6_000L), gv.openedAt);
    }

    @Test
    public void aShortEstimateIsRetriedOnTheSameUrl() throws IOException {
        FakeClock clock = new FakeClock(0);
        Googlevideo gv = new Googlevideo(clock, 8_000); // serves 3 s after the estimate
        ReadinessGate gate = new ReadinessGate(5_000, 5_000, clock);

        assertEquals(LENGTH, gate.wrap(() -> gv).createDataSource().open(VIDEO));
        assertEquals(Arrays.asList(5_000L, 3_000L), clock.sleeps);
        assertEquals(Arrays.asList(0L, 5_000L, 8_000L), gv.openedAt);
    }

    @Test
    public void refusalsPastTheRetryWindowSurface() {
        FakeClock clock = new FakeClock(0);
        Googlevideo gv = new Googlevideo(clock, Long.MAX_VALUE);
        ReadinessGate gate = new ReadinessGate(5_000, 5_000, clock);

        assertThrows(HttpDataSource.InvalidResponseCodeException.class,
                () -> gate.wrap(() -> gv).createDataSource().open(VIDEO));
        // Asked at 0, waited to 5 s, retried 3 and 6 s apart (opens at 0, 5, 8 and 14 s); there is
        // no third backoff, so the refusal surfaces for route recovery.
        assertEquals(Arrays.asList(5_000L, 3_000L, 6_000L), clock.sleeps);
        assertEquals(4, gv.openedAt.size());
        assertEquals(1_000, gate.holdLeftMs());
    }

    @Test
    public void onceTheAnswerServedA403IsARealRefusal() throws IOException {
        FakeClock clock = new FakeClock(0);
        Googlevideo gv = new Googlevideo(clock, 5_000);
        ReadinessGate gate = new ReadinessGate(5_000, 5_000, clock);
        assertEquals(15_000, gate.holdLeftMs());
        gate.wrap(() -> gv).createDataSource().open(VIDEO);
        assertEquals(0, gate.holdLeftMs());

        clock.now += 1; // a request sent after the answer was served
        Googlevideo refusing = new Googlevideo(clock, Long.MAX_VALUE);
        DataSource audio = gate.wrap(() -> refusing).createDataSource();
        assertThrows(HttpDataSource.InvalidResponseCodeException.class, () -> audio.open(AUDIO));
        assertEquals("no extra wait or retry after the answer served", 1, clock.sleeps.size());
    }

    @Test
    public void aTrackRefusedJustBeforeTheOtherWasServedIsStillRetried() throws IOException {
        // Audio and video both wait for readyAt; audio's request is served, video's - sent at the
        // same moment - is refused. It is part of the wait, not a real refusal.
        FakeClock clock = new FakeClock(0);
        ReadinessGate gate = new ReadinessGate(5_000, 5_000, clock);
        Googlevideo audio = new Googlevideo(clock, 5_000);
        Googlevideo video = new Googlevideo(clock, 8_000) {
            @Override public long open(DataSpec dataSpec) throws IOException {
                if (clock.now == 5_000) {
                    // The audio open lands while this request is in flight.
                    gate.wrap(() -> audio).createDataSource().open(AUDIO);
                }
                return super.open(dataSpec);
            }
        };

        assertEquals(LENGTH, gate.wrap(() -> video).createDataSource().open(VIDEO));
        assertEquals(Arrays.asList(0L, 5_000L, 8_000L), video.openedAt);
    }

    @Test
    public void aServedPlaylistIsNotServedMedia() throws IOException {
        // HLS: the playlist comes from manifest.googlevideo.com at once; the segments are held back.
        FakeClock clock = new FakeClock(0);
        ReadinessGate gate = new ReadinessGate(5_000, 5_000, clock);
        DataSpec playlist = new DataSpec(Uri.parse("https://manifest.googlevideo.com/api/manifest/"
                + "hls_playlist/expire/1/id/x/itag/230/playlist/index.m3u8"));
        gate.wrap(() -> new Googlevideo(clock, 0)).createDataSource().open(playlist);
        assertEquals(15_000, gate.holdLeftMs());

        clock.now += 1;
        Googlevideo segments = new Googlevideo(clock, 5_000);
        DataSpec segment = new DataSpec(Uri.parse("https://rr1---sn-x.googlevideo.com/videoplayback/"
                + "id/x/itag/230/sq/1/goap/clen%3D1/file/seg.ts"));
        gate.wrap(() -> segments).createDataSource().open(segment);
        assertEquals(Arrays.asList(1L, 5_000L), segments.openedAt);
        assertEquals(0, gate.holdLeftMs());
    }

    @Test
    public void otherFailuresAreNotRetried() {
        FakeClock clock = new FakeClock(0);
        ReadinessGate gate = new ReadinessGate(5_000, 5_000, clock);
        DataSource failing = new Googlevideo(clock, 0) {
            @Override public long open(DataSpec dataSpec) throws IOException {
                throw new HttpDataSource.InvalidResponseCodeException(404, "Not Found", null,
                        Collections.emptyMap(), dataSpec, new byte[0]);
            }
        };

        assertThrows(HttpDataSource.InvalidResponseCodeException.class,
                () -> gate.wrap(() -> failing).createDataSource().open(VIDEO));
        assertEquals(0, clock.sleeps.size());
    }

    @Test
    public void cachedBytesNeitherWaitNorCountAsServed() throws IOException {
        SimpleCache cache = new SimpleCache(mTemp.newFolder(), new NoOpCacheEvictor(),
                new StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()));
        try {
            FakeClock clock = new FakeClock(0);
            // The audio chunk is on disk from an earlier play.
            readAll(new CacheDataSource.Factory().setCache(cache)
                    .setUpstreamDataSourceFactory(() -> new Googlevideo(clock, 0))
                    .createDataSource(), AUDIO);

            Googlevideo gv = new Googlevideo(clock, 8_000);
            ReadinessGate gate = new ReadinessGate(5_000, 5_000, clock);
            DataSource.Factory chunks = new CacheDataSource.Factory().setCache(cache)
                    .setUpstreamDataSourceFactory(gate.wrap(() -> gv));

            readAll(chunks.createDataSource(), AUDIO);
            assertEquals("a cache hit never reaches googlevideo", 0, gv.openedAt.size());
            assertEquals(Collections.emptyList(), clock.sleeps);

            // So the uncached video chunk is still waited for and retried.
            readAll(chunks.createDataSource(), VIDEO);
            assertEquals(Arrays.asList(0L, 5_000L, 8_000L), gv.openedAt);
        } finally {
            cache.release();
        }
    }

    @Test
    public void oneGatePerAnswer() {
        MediaItemFormatInfo withAds = answer(60_000, 5_000);
        ReadinessGate gate = ReadinessGate.forAnswer(withAds);
        assertSame(gate, ReadinessGate.forAnswer(withAds));
        assertEquals(60_000, gate.readyAtMs);
        assertEquals(5_000, gate.prerollMs);
        assertNull(ReadinessGate.forAnswer(answer(0, 0)));
        assertEquals(0, ReadinessGate.holdLeftMs(answer(60_000, 5_000))); // another answer: no gate yet
    }

    private static void readAll(DataSource source, DataSpec spec) throws IOException {
        source.open(spec);
        byte[] buffer = new byte[256];
        while (source.read(buffer, 0, buffer.length) != -1) {
            // drain
        }
        source.close();
    }

    /** A format info that only knows its readiness. */
    private static MediaItemFormatInfo answer(long readyAtMs, long prerollMs) {
        return (MediaItemFormatInfo) Proxy.newProxyInstance(MediaItemFormatInfo.class.getClassLoader(),
                new Class<?>[] {MediaItemFormatInfo.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getMediaReadyAtMs": return readyAtMs;
                        case "getPrerollWaitMs": return prerollMs;
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == args[0];
                        default:
                            Class<?> type = method.getReturnType();
                            if (type == boolean.class) return false;
                            if (type == long.class) return 0L;
                            return type.isPrimitive() ? 0 : null;
                    }
                });
    }
}
