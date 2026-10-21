package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/** VOIDXDEV(lyrics): naming the song, choosing LRCLIB's answer and reading LRC. */
public class LyricsTest {
    private static final String VERSE = "one\ntwo\nthree\nfour";
    private static final String SYNCED = "[00:01.00]one\n[00:02.00]two\n[00:03.00]three\n[00:04.00]four";

    @Test
    public void titlesLoseTheirPackaging() {
        assertEquals("Ooroda Oththa Don", Lyrics.cleanTitle(
                "Ooroda Oththa Don Video Song | Baththa | Vijay Sethupathi | @SaiAbhyankkar| Balaji", "Sai Abhyankkar"));
        assertEquals("Ooroda Oththa Don", Lyrics.cleanTitle("Ooroda Oththa Don - Lyric | Baththa", "Sai Abhyankkar"));
        assertEquals("Ooroda Oththa Don", Lyrics.cleanTitle("Ooroda Oththa Don (From \"Baththa\")", "Sai Abhyankkar"));
        assertEquals("Never Gonna Give You Up", Lyrics.cleanTitle(
                "Rick Astley - Never Gonna Give You Up (Official Music Video)", "Rick Astley"));
        assertEquals("Dracula", Lyrics.cleanTitle("Dracula (feat. JENNIE)", "Tame Impala"));
    }

    @Test
    public void titlesKeepWhatNamesADifferentRecording() {
        assertEquals("Song (Remix)", Lyrics.cleanTitle("Song (Remix)", "Someone"));
        assertEquals("Video Killed the Radio Star", Lyrics.cleanTitle("Video Killed the Radio Star", "The Buggles"));
    }

    @Test
    public void anArtistInCommonIsTheSameSong() {
        assertTrue(Lyrics.sameArtist("Sai Abhyankkar",
                "Thamizh Aadhavan, KK Nagar Gana Jeeva, Sai Abhyankkar & Vangal Pulla Vicky"));
        assertTrue(Lyrics.sameArtist("A. R. Rahman, Shreya Ghoshal", "A.R. Rahman"));
        assertFalse(Lyrics.sameArtist("Adele", "Lionel Richie"));
    }

    @Test
    public void pickPrefersTheTimedSameLengthRecordingByTheArtist() {
        Lyrics.Hit stranger = new Lyrics.Hit("Someone Else", 212, SYNCED, VERSE);
        Lyrics.Hit vandalised = new Lyrics.Hit("Sai Abhyankkar", 212, "[00:00.00]probe", "probe");
        Lyrics.Hit longer = new Lyrics.Hit("Sai Abhyankkar", 230, SYNCED, VERSE);
        Lyrics.Hit same = new Lyrics.Hit("Sai Abhyankkar", 213, SYNCED, VERSE);
        assertSame(same, Lyrics.pick(Arrays.asList(stranger, vandalised, longer, same), "Sai Abhyankkar", 212));
        assertTrue(Lyrics.timed(same, 212));
        // a music video with an intro: nothing is its length, so the nearest is shown untimed
        Lyrics.Hit nearest = Lyrics.pick(Arrays.asList(longer, same), "Sai Abhyankkar", 260);
        assertSame(longer, nearest);
        assertFalse(Lyrics.timed(nearest, 260));
        assertNull(Lyrics.pick(Arrays.asList(stranger, vandalised), "Sai Abhyankkar", 212));
    }

    @Test
    public void lrcIsReadInTimeOrder() {
        Lyrics lyrics = Lyrics.parseLrc("[ar:Someone]\n[00:12.50]first\n[00:20.00][01:00.123]chorus <00:21.00>word\n"
                + "[00:30.00]\n[00:25.5]third", "t", "a", "s");
        assertEquals(Arrays.asList("first", "chorus word", "third", "chorus word"), lyrics.lines);
        assertArrayEquals(new long[]{12_500, 20_000, 25_500, 60_123}, lyrics.times);
    }

    @Test
    public void appleTtmlIsReadLineByLine() throws Exception {
        String ttml = "<tt xmlns=\"http://www.w3.org/ns/ttml\" xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\">"
                + "<body dur=\"3:33.1\"><div>"
                + "<p begin=\"1:02.5\" end=\"1:04\"><span begin=\"1:02.5\" end=\"1:03\">Ne</span>"
                + "<span begin=\"1:03\" end=\"1:03.2\">ver</span> <span begin=\"1:03.2\" end=\"1:04\">gonna,</span>"
                + "<span ttm:role=\"x-bg\"><span begin=\"1:03\" end=\"1:04\">(ooh)</span></span>"
                + "<span ttm:role=\"x-translation\">Jamais</span></p>"
                + "<p begin=\"12.25\" end=\"13\">first line</p>"
                + "<p begin=\"00:01:10.000\"><span begin=\"70\" end=\"71\">give</span> "
                + "<span ttm:role=\"x-bg\"><span begin=\"70\" end=\"71\">you up</span></span></p>"
                + "</div></body></tt>";
        Lyrics lyrics = Lyrics.parseTtml(ttml, "t", "a", "s");
        assertEquals(Arrays.asList("first line", "Never gonna, (ooh)", "give (you up)"), lyrics.lines);
        assertArrayEquals(new long[]{12_250, 62_500, 70_000}, lyrics.times);
        assertNull(Lyrics.parseTtml("<tt><body><div><p>one</p><p>two</p></div></body></tt>", "t", "a", "s").times);
        assertEquals(62_345, Lyrics.ttmlTime("62.345s"));
        assertEquals(1_500, Lyrics.ttmlTime("1500ms"));
        assertEquals(-1, Lyrics.ttmlTime(""));
    }

    @Test
    public void qqMusicQrcLosesItsWordStampsAndCredits() {
        String qrc = "<?xml version=\"1.0\"?><QrcInfos><Lyric_1 LyricType=\"1\" LyricContent=\"[ti:Never Gonna Give You Up]\n"
                + "[0,1090]Never(0,113) (113,23)Gonna(136,113) (249,23)Give(272,91) (363,23)You(386,68) (454,22)Up(476,46)"
                + " (522,23)-(794,23) (817,23)Rick(840,91) (931,22)Astley(953,137)\n"
                + "[1094,1063]Lyrics (1094,124)by：(1218,114)Mike (1332,136)Stock(2013,144)\n"
                + "[19311,3051]We&apos;re (19311,228)no (19539,176)strangers (19715,195)to (19910,236)love(20146,2216)\n"
                + "[23031,3513]You (23031,155)know(23186,300)\"/></QrcInfos>";
        Lyrics lyrics = LyricsSources.dropCredits(LyricsSources.parseQrc(qrc, "Never Gonna Give You Up", "Rick Astley", "s"));
        assertEquals(Arrays.asList("We're no strangers to love", "You know"), lyrics.lines);
        assertArrayEquals(new long[]{19_311, 23_031}, lyrics.times);
    }

    @Test
    public void geniusPagesGiveTheirWordsOnly() {
        String html = "<div id=\"lyrics-root\"><div data-lyrics-container=\"true\" class=\"a\">"
                + "<div data-exclude-from-selection=\"true\"><button>271 Contributors</button><div>x</div></div>"
                + "[Intro]<br/><a href=\"/1\"><span>(Ooh)</span></a><br/><br/>[Verse 1]<br/>We&#x27;re no strangers"
                + "<br>to love &amp; you</div><div>other</div>"
                + "<div data-lyrics-container=\"true\">[Chorus]<br/>Never gonna</div></div>";
        assertEquals(Arrays.asList("(Ooh)", "", "We're no strangers", "to love & you", "Never gonna"),
                LyricsSources.geniusLines(html));
    }

    @Test
    public void titlesMatchOncePackagingIsOff() {
        assertTrue(LyricsSources.sameTitle("Ooroda Oththa Don (From \"Baththa\")", "Ooroda Oththa Don"));
        assertFalse(LyricsSources.sameTitle("Hello", "Someone Like You"));
    }

    @Test
    public void aSongKeepsWhatEachProviderAnswered() {
        Lyrics timed = Lyrics.parseLrc(SYNCED, "t", "a", "s");
        Lyrics.Song song = new Lyrics.Song(new LyricsSources.Query("t", "a", 212, null), false, null);
        assertFalse(song.asked(0));
        assertSame(timed, song.answer(0, timed));
        assertNull(song.answer(1, null));
        assertTrue(song.asked(1));
        assertNull(song.lyrics(1)); // asked, and had none
        assertEquals(timed.lines, song.lyrics(0).lines);
        assertNull(song.lyrics(0).times); // not this video's timing: shown untimed
    }

    @Test
    public void lengthsReadAsSeconds() {
        assertEquals(226, Lyrics.seconds("3:46"));
        assertEquals(3723, Lyrics.seconds("1:02:03"));
        assertEquals(0, Lyrics.seconds(""));
    }
}
