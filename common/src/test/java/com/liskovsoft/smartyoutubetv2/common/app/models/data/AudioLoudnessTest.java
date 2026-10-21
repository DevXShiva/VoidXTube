package com.liskovsoft.smartyoutubetv2.common.app.models.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * NEWTUBE(loudness): issue #15. YouTube's rule (attenuate by the excess over the target, never
 * amplify) and the choice of which loudness value applies to the playing track.
 */
public class AudioLoudnessTest {
    private static final float DELTA = 0.001f;

    @Test
    public void attenuatesOnlyTheExcessOverTheTarget() {
        // Rick Astley, +0.99 dB: YouTube's Android app played it at -0.98 dB.
        assertEquals(0.892f, AudioLoudness.gain(0.99f), DELTA);
        assertEquals(0.501f, AudioLoudness.gain(6f), DELTA);
        // The old formula put +5.7 dB at 0.036 (-29 dB); YouTube's rule is monotonic.
        assertEquals(0.519f, AudioLoudness.gain(5.7f), DELTA);
    }

    @Test
    public void neverAmplifiesAndUnknownIsUnity() {
        assertEquals(1f, AudioLoudness.gain(-4.71f), 0f);
        assertEquals(1f, AudioLoudness.gain(0f), 0f);
        assertEquals(1f, AudioLoudness.gain(null), 0f);
        assertEquals(1f, AudioLoudness.gain(Float.NaN), 0f);
        assertEquals(1f, AudioLoudness.gain(Float.POSITIVE_INFINITY), 0f);
    }

    @Test
    public void thePlayingFormatsOwnValueWins() {
        AudioLoudness loudness = new AudioLoudness(0.4f, Arrays.asList(
                entry("140", false, null, 0.4f),
                entry("251", false, null, 0.38f),
                entry("251", true, null, -1.91f)));

        assertEquals(0.38f, loudness.dbFor("251", null, null), 0f);
        // The stable-volume variant is quieter than the target: played at unity, as YouTube does.
        assertEquals(-1.91f, loudness.dbFor("251-drc", null, null), 0f);
    }

    @Test
    public void unknownFormatsFallBackToTheTrackValue() {
        AudioLoudness loudness = new AudioLoudness(0.99f, Collections.singletonList(entry("140", false, null, 0.99f)));

        assertEquals(0.99f, loudness.dbFor(null, null, null), 0f); // progressive
        assertEquals(0.99f, loudness.dbFor("1", null, null), 0f); // an HLS variant
        assertEquals(0.99f, loudness.dbFor("140-drc", null, null), 0f); // no such variant
        assertNull(new AudioLoudness(null, Collections.emptyList()).dbFor("140", null, null));
    }

    @Test
    public void dubsSharingAnItagAreToldApartByLanguage() {
        // Shaped like CoComelon's answer: one itag per dub, each with its own loudness.
        AudioLoudness loudness = new AudioLoudness(1.3f, Arrays.asList(
                entry("140", false, "en\u2010us (original)", 1.3f),
                entry("140", false, "es (dubbed-auto)", 4.38f),
                entry("140", false, "fr (dubbed-auto)", -3.73f)));

        // DASH: the manifest's label is the display string verbatim.
        assertEquals(4.38f, loudness.dbFor("140", "es (dubbed-auto)", "es"), 0f);
        // Only the code to go on: the exoNameFix hyphen is undone and case ignored.
        assertEquals(1.3f, loudness.dbFor("140", null, "en-us"), 0f);
        // No language match among different values: ambiguous, so the track value.
        assertEquals(1.3f, loudness.dbFor("140", null, "de"), 0f);
    }

    @Test
    public void anExactLabelBeatsTheSharedLanguageCode() {
        // An original and its audio-description track share the itag and the language code.
        AudioLoudness loudness = new AudioLoudness(6f, Arrays.asList(
                entry("251", false, "en (original)", 6f),
                entry("251", false, "en (descriptive)", 0f)));

        assertEquals(0f, loudness.dbFor("251", "en (descriptive)", "en"), 0f);
        assertEquals(6f, loudness.dbFor("251", "en (original)", "en"), 0f);
    }

    @Test
    public void anItagSharedWithOneValueNeedsNoLanguage() {
        AudioLoudness loudness = new AudioLoudness(null, Arrays.asList(
                entry("251", false, "en (original)", 2f),
                entry("251", false, "es (dubbed-auto)", 2f)));

        assertEquals(2f, loudness.dbFor("251", null, "de"), 0f);
    }

    @Test
    public void readsTheAnswersAudioFormatsOnly() {
        MediaItemFormatInfo info = formatInfo(0.99f, Arrays.asList(
                format("137", "video/mp4; codecs=\"avc1.640028\"", false, 3f),
                format("140", "audio/mp4; codecs=\"mp4a.40.2\"", false, 0.99f),
                format("251", "audio/webm; codecs=\"opus\"", false, 0.98f),
                format("249", "audio/webm; codecs=\"opus\"", false, null)));

        AudioLoudness loudness = AudioLoudness.from(info);

        assertEquals(0.98f, loudness.dbFor("251", null, null), 0f);
        assertEquals(0.99f, loudness.dbFor("137", null, null), 0f); // video rows are not read
        assertEquals(0.99f, loudness.dbFor("249", null, null), 0f); // no value of its own
    }

    @Test
    public void anAnswerWithoutLoudnessGivesNone() {
        assertNull(AudioLoudness.from(formatInfo(null, Collections.singletonList(
                format("140", "audio/mp4; codecs=\"mp4a.40.2\"", false, null)))));
        assertNull(AudioLoudness.from(null));
    }

    private static AudioLoudness.Entry entry(String itag, boolean drc, String language, float db) {
        return new AudioLoudness.Entry(itag, drc, language, db);
    }

    private static MediaItemFormatInfo formatInfo(Float trackDb, List<MediaFormat> formats) {
        return (MediaItemFormatInfo) Proxy.newProxyInstance(MediaItemFormatInfo.class.getClassLoader(),
                new Class<?>[]{MediaItemFormatInfo.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getAudioLoudnessDb": return trackDb;
                        case "getAdaptiveFormats": return formats;
                        default: return defaultValue(method.getReturnType());
                    }
                });
    }

    private static MediaFormat format(String itag, String mimeType, boolean drc, Float db) {
        return (MediaFormat) Proxy.newProxyInstance(MediaFormat.class.getClassLoader(),
                new Class<?>[]{MediaFormat.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getITag": return itag;
                        case "getMimeType": return mimeType;
                        case "isDrc": return drc;
                        case "getLoudnessDb": return db;
                        default: return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        return null;
    }
}
