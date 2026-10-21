package com.newtube.mobile.ui.browse;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class CollabNamesTest {
    @Test
    public void splitsTwoChannelCollabs() {
        assertArrayEquals(new String[] {"Vijay Television", "JioHotstar Tamil"},
                VideoCardAdapter.VideoViewHolder.collabNames("Vijay Television and JioHotstar Tamil"));
        assertNull(VideoCardAdapter.VideoViewHolder.collabNames("Marques Brownlee"));
        assertNull(VideoCardAdapter.VideoViewHolder.collabNames("MKBHD and 2 more"));
        assertNull(VideoCardAdapter.VideoViewHolder.collabNames("A and B and C"));
    }
}
