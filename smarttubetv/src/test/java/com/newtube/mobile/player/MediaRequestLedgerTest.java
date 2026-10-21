package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.net.Uri;

import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * NEWTUBE(wall-memory): one open's media requests, as the wall's signature reads them - r11's jump
 * case: 0-10 s served, then the first request past 60 s (start 70001) refused.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class MediaRequestLedgerTest {
    @Test
    public void theJumpCase() {
        MediaRequestLedger ledger = new MediaRequestLedger();
        assertEquals(-1, ledger.forbiddenStartMs());
        assertEquals(-1, ledger.lowestServedStartMs());
        ledger.onServed(5_000);
        ledger.onServed(0);
        ledger.onServed(10_000);
        ledger.onForbidden(70_001);
        assertEquals(70_001, ledger.forbiddenStartMs());
        assertEquals(0, ledger.lowestServedStartMs());
        assertEquals(10_000, ledger.highestServedStartMs());

        ledger.reset(); // the reload is a new open
        assertEquals(-1, ledger.forbiddenStartMs());
        assertEquals(-1, ledger.highestServedStartMs());
    }

    @Test
    public void onlyA403IsForbidden() {
        assertTrue(MediaRequestLedger.isForbidden(http(403)));
        assertTrue(MediaRequestLedger.isForbidden(new IOException("wrapped", http(403))));
        assertFalse(MediaRequestLedger.isForbidden(http(404)));
        assertFalse(MediaRequestLedger.isForbidden(new IOException("timeout")));
        assertFalse(MediaRequestLedger.isForbidden(null));
    }

    private static HttpDataSource.InvalidResponseCodeException http(int code) {
        return new HttpDataSource.InvalidResponseCodeException(code, "test", null,
                Collections.<String, List<String>>emptyMap(),
                new DataSpec(Uri.parse("https://rr1.googlevideo.com/videoplayback")), new byte[0]);
    }
}
