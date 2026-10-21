package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.ExoFormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorManager;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.MediaTrack;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.TrackFormat;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * NEWTUBE(audio-track): the Audio track sheet lists language variants, not codecs or bitrates.
 * Robolectric only because building a FormatItem touches android.text.TextUtils.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class AudioTrackChoicesTest {
    private static final String EN = "en (original)";
    private static final String ES = "es (dubbed)";
    private static final String FR = "fr (dubbed-auto)";

    private static FormatItem audio(String id, String codecs, int bitrate, String language, boolean selected) {
        return audio(id, codecs, bitrate, 2, language, selected);
    }

    private static FormatItem audio(String id, String codecs, int bitrate, int channels, String language,
                                    boolean selected) {
        MediaTrack track = MediaTrack.forRendererIndex(TrackSelectorManager.RENDERER_INDEX_AUDIO);
        String mime = codecs.startsWith("opus") ? "audio/opus" : "audio/mp4a-latm";
        track.format = TrackFormat.createAudio(id, mime, codecs, bitrate, channels, 48_000, language, false);
        track.isSelected = selected;
        return ExoFormatItem.from(track);
    }

    /** One variant as YouTube ships it: AAC 139/140 and Opus 249/251. */
    private static List<FormatItem> variant(String language, String playingId) {
        return Arrays.asList(
                audio("139", "mp4a.40.5", 48_000, language, "139".equals(playingId)),
                audio("140", "mp4a.40.2", 129_000, language, "140".equals(playingId)),
                audio("249", "opus", 50_000, language, "249".equals(playingId)),
                audio("251", "opus", 125_000, language, "251".equals(playingId)));
    }

    private static List<AudioTrackChoices.Choice> choices(List<FormatItem> formats) {
        return AudioTrackChoices.from(formats, language -> language == null ? "Default"
                : AudioTrackLabel.format(language, Locale.ENGLISH, "original", "dubbed",
                        "auto-dubbed", "audio description"), Locale.ENGLISH);
    }

    private static List<FormatItem> dubbedVideo(String playingLanguage, String playingId) {
        List<FormatItem> formats = new ArrayList<>();
        // Manifest order puts a dub first, as YouTube's often does.
        formats.addAll(variant(ES, ES.equals(playingLanguage) ? playingId : null));
        formats.addAll(variant(EN, EN.equals(playingLanguage) ? playingId : null));
        formats.addAll(variant(FR, FR.equals(playingLanguage) ? playingId : null));
        return formats;
    }

    @Test
    public void oneRowPerLanguageVariantSortedByName() {
        List<AudioTrackChoices.Choice> rows = choices(dubbedVideo(EN, "251"));

        assertEquals(3, rows.size());
        assertEquals("English (original)", rows.get(0).label);
        assertEquals("French (auto-dubbed)", rows.get(1).label);
        assertEquals("Spanish (dubbed)", rows.get(2).label);
        assertEquals(EN, rows.get(0).language);
    }

    @Test
    public void theCheckSitsOnTheVariantThatIsPlaying() {
        List<AudioTrackChoices.Choice> rows = choices(dubbedVideo(ES, "251"));

        AudioTrackChoices.Choice playing = AudioTrackChoices.selected(rows);
        assertEquals("Spanish (dubbed)", playing.label);
        int checked = 0;
        for (AudioTrackChoices.Choice row : rows) {
            checked += row.selected ? 1 : 0;
        }
        assertEquals(1, checked);
    }

    @Test
    public void aTapKeepsThePlayingCodecAndTakesItsBestBitrate() {
        // Opus is playing: French must switch to French Opus 251, not the (higher-bitrate) AAC 140.
        List<AudioTrackChoices.Choice> rows = choices(dubbedVideo(EN, "251"));
        assertEquals("251", rows.get(1).item.getFormatId());
        assertEquals(FR, rows.get(1).item.getLanguage());

        // AAC is playing: the same row now selects AAC 140.
        rows = choices(dubbedVideo(EN, "139"));
        assertEquals("140", rows.get(1).item.getFormatId());
    }

    @Test
    public void aTapKeepsThePlayingTracksStableVolumeSetting() {
        // Each variant also ships a "-drc" (stable volume) Opus track, a touch bigger than 251.
        List<FormatItem> formats = new ArrayList<>();
        for (String language : Arrays.asList(EN, ES)) {
            formats.add(audio("251", "opus", 125_000, language, false));
            formats.add(audio("251-drc", "opus", 126_000, language, false));
        }

        // Plain 251 playing: Spanish is the plain 251 too.
        formats.set(0, audio("251", "opus", 125_000, EN, true));
        assertEquals("251", choices(formats).get(1).item.getFormatId());

        // Stable volume playing: Spanish keeps it.
        formats.set(0, audio("251", "opus", 125_000, EN, false));
        formats.set(1, audio("251-drc", "opus", 126_000, EN, true));
        assertEquals("251-drc", choices(formats).get(1).item.getFormatId());

        // Nothing playing yet: the plain track, not the DRC one, although its bitrate is lower.
        formats.set(1, audio("251-drc", "opus", 126_000, EN, false));
        assertEquals("251", choices(formats).get(1).item.getFormatId());
    }

    @Test
    public void aTapKeepsStereoOverABiggerSurroundTrack() {
        List<FormatItem> formats = new ArrayList<>(Arrays.asList(
                audio("140", "mp4a.40.2", 129_000, 2, EN, true),
                audio("140", "mp4a.40.2", 129_000, 2, ES, false),
                audio("256", "mp4a.40.5", 192_000, 6, ES, false)));

        assertEquals("140", choices(formats).get(1).item.getFormatId());

        formats.set(0, audio("140", "mp4a.40.2", 129_000, 2, EN, false));
        assertEquals("140", choices(formats).get(1).item.getFormatId()); // nothing playing: stereo
    }

    @Test
    public void anEmptyAndAMissingLanguageAreOneDefaultRow() {
        List<FormatItem> formats = Arrays.asList(
                audio("251", "opus", 125_000, null, true),
                audio("140", "mp4a.40.2", 129_000, "", false),
                audio("251", "opus", 125_000, ES, false));

        List<AudioTrackChoices.Choice> rows = choices(formats);

        assertEquals(2, rows.size());
        assertEquals("Default", rows.get(0).label);
        assertTrue(rows.get(0).selected);
    }

    @Test
    public void nothingPlayingYetTakesTheHighestBitrate() {
        List<AudioTrackChoices.Choice> rows = choices(dubbedVideo(null, null));

        assertNull(AudioTrackChoices.selected(rows));
        for (AudioTrackChoices.Choice row : rows) {
            assertEquals("140", row.item.getFormatId());
            assertFalse(row.selected);
        }
    }

    @Test
    public void aSingleLanguageVideoIsOneRow() {
        List<FormatItem> formats = variant(null, "251");
        List<AudioTrackChoices.Choice> rows = choices(formats);

        assertEquals(1, rows.size());
        assertNull(rows.get(0).language);
        assertEquals("Default", rows.get(0).label);
        assertTrue(rows.get(0).selected);
    }

    @Test
    public void anUntaggedOriginalBesideADubIsTwoRows() {
        List<FormatItem> formats = new ArrayList<>(variant(null, "251"));
        formats.addAll(variant(ES, null));

        List<AudioTrackChoices.Choice> rows = choices(formats);

        assertEquals(2, rows.size());
        assertTrue(AudioTrackChoices.selected(rows).language == null);
    }

    @Test
    public void noFormatsMeansNoRows() {
        assertTrue(choices(null).isEmpty());
        assertTrue(choices(new ArrayList<>()).isEmpty());
    }

    @Test
    public void theRowCarriesTheTrackInstanceOfThisVideo() {
        List<FormatItem> formats = dubbedVideo(EN, "251");
        List<AudioTrackChoices.Choice> rows = choices(formats);

        // Spanish Opus 251 is the fourth item of the manifest-first Spanish variant.
        assertSame(formats.get(3), rows.get(2).item);
    }
}
