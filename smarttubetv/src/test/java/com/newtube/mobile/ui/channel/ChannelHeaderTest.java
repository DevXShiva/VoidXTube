package com.newtube.mobile.ui.channel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.InputStream;
import java.util.Scanner;

/** VOIDXDEV(channel header, #10): the fixture is a real MWEB browse header (Vijay Television, 2026-10-19). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ChannelHeaderTest {
    @Test
    public void readsTheHeader() throws Exception {
        InputStream in = getClass().getClassLoader().getResourceAsStream("channel_header_mweb.json");
        String json = new Scanner(in, "UTF-8").useDelimiter("\\A").next();
        ChannelHeader.Info info = ChannelHeader.parse(new JSONObject(json));
        assertEquals("Vijay Television", info.title);
        assertEquals("@VijayTelevision", info.handle);
        assertEquals("28.8M subscribers • 149K videos", info.stats);
        assertTrue(info.description.startsWith("Vijay Television (\"Vijay\")"));
        assertTrue(info.avatarUrl.startsWith("https://yt3."));
        assertTrue(info.bannerUrl.contains("=w1138"));
        assertNull(ChannelHeader.parse(new JSONObject("{}")));
    }
}
