package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;

import android.app.Application;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** NEWTUBE(playback-identity): the synthetic wall's byte, from the stream's length and duration. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class DebugMediaShaperWallTest {
    private static Uri url(String params) {
        return Uri.parse("https://rr1---sn-a.googlevideo.com/videoplayback?itag=137&ei=AAA" + params);
    }

    @Test
    public void theWallIsTheDurationsShareOfTheLength() {
        assertEquals(3_000_000L, DebugMediaShaper.wallByte(url("&clen=10000000&dur=200.000"), 60));
        assertEquals(2_562_917L, DebugMediaShaper.wallByte(url("&clen=50000000&dur=1170.541"), 60));
    }

    @Test
    public void withoutLengthOrDurationThereIsNoWall() {
        assertEquals(-1L, DebugMediaShaper.wallByte(url("&dur=200.0"), 60));
        assertEquals(-1L, DebugMediaShaper.wallByte(url("&clen=10000000"), 60));
        assertEquals(-1L, DebugMediaShaper.wallByte(url("&clen=10000000&dur=45.0"), 60)); // shorter
        assertEquals(-1L, DebugMediaShaper.wallByte(url("&clen=x&dur=200"), 60));
    }
}
