package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MusicLinkTest {
    @Test
    public void onlyAnswersWithAMusicVideoTypeAreMusic() {
        assertTrue(MusicLink.isMusic("{\"playabilityStatus\":{\"status\":\"UNPLAYABLE\"},"
                + "\"videoDetails\":{\"musicVideoType\":\"MUSIC_VIDEO_TYPE_OMV\"}}"));
        assertTrue(MusicLink.isMusic("{\"videoDetails\": {\"musicVideoType\": \"MUSIC_VIDEO_TYPE_ATV\"}}"));
        assertFalse(MusicLink.isMusic("{\"playabilityStatus\":{\"status\":\"UNPLAYABLE\"},\"videoDetails\":{}}"));
        assertFalse(MusicLink.isMusic("{}"));
    }
}
