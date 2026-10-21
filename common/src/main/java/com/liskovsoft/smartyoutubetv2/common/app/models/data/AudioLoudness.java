package com.liskovsoft.smartyoutubetv2.common.app.models.data;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * NEWTUBE(loudness): the loudness YouTube measured for one /player answer, and the rule YouTube's own
 * players apply to it (issue #15).
 *
 * <p>Every value is in dB relative to the answer's loudness target (-14 LKFS on the regular clients):
 * positive means louder than the target. YouTube only ever turns a track DOWN by that excess, never
 * up: {@code gain = min(1, 10^(-dB/20))}. Its Android app played Rick Astley (+0.99 dB) at -0.98 dB,
 * where the inherited SmartTube formula played every VISIONOS answer at a flat -6 dB.
 *
 * <p>The playing format's own value wins (answers give each itag, stable-volume variant and dub its
 * own), then the answer's track value. Unknown loudness is unity gain, never a guess.
 */
public final class AudioLoudness {
    private static final String DRC_SUFFIX = "-drc";
    private static final float SAME_DB = 0.01f;

    @Nullable private final Float mTrackDb;
    private final List<Entry> mFormats;

    /** One audio format's loudness. {@code language} is the format's display language, e.g. "en (original)". */
    public static final class Entry {
        final String itag;
        final boolean drc;
        @Nullable final String language;
        final float db;

        public Entry(String itag, boolean drc, @Nullable String language, float db) {
            this.itag = itag;
            this.drc = drc;
            this.language = language;
            this.db = db;
        }
    }

    public AudioLoudness(@Nullable Float trackDb, List<Entry> formats) {
        mTrackDb = isUsable(trackDb) ? trackDb : null;
        mFormats = formats;
    }

    /** Null when the answer carries no loudness at all. */
    @Nullable
    public static AudioLoudness from(@Nullable MediaItemFormatInfo formatInfo) {
        if (formatInfo == null) {
            return null;
        }

        List<Entry> formats = new ArrayList<>();
        List<MediaFormat> adaptive = formatInfo.getAdaptiveFormats();
        if (adaptive != null) {
            for (MediaFormat format : adaptive) {
                Float db = format.getLoudnessDb();
                String mimeType = format.getMimeType();
                if (isUsable(db) && format.getITag() != null && mimeType != null && mimeType.startsWith("audio/")) {
                    formats.add(new Entry(format.getITag(), format.isDrc(), format.getLanguage(), db));
                }
            }
        }

        Float trackDb = formatInfo.getAudioLoudnessDb();
        return formats.isEmpty() && !isUsable(trackDb) ? null
                : new AudioLoudness(trackDb, formats.isEmpty() ? Collections.emptyList() : formats);
    }

    /** YouTube's rule: attenuate by the excess over the target, never amplify. Unknown is unity. */
    public static float gain(@Nullable Float db) {
        if (!isUsable(db) || db <= 0) {
            return 1f;
        }
        return (float) Math.pow(10, -db / 20.0);
    }

    /**
     * The loudness of the playing track, given its player format: {@code formatId} is the itag the
     * DASH manifest writes ("251", or "251-drc" for the stable-volume variant), {@code label} the
     * display language the manifest writes for a dub ("es (dubbed-auto)") and {@code language} its
     * code. Falls back to the answer's track value when the format cannot be told apart (HLS, a
     * progressive stream, an itag shared by dubs with different values). The SABR experiment's
     * formats carry the bare itag for both variants, so its stable-volume track is read as the
     * regular one (they differ by about 0.4-2 dB).
     */
    @Nullable
    public Float dbFor(@Nullable String formatId, @Nullable String label, @Nullable String language) {
        if (formatId == null || mFormats.isEmpty()) {
            return mTrackDb;
        }

        boolean drc = formatId.endsWith(DRC_SUFFIX);
        String itag = drc ? formatId.substring(0, formatId.length() - DRC_SUFFIX.length()) : formatId;
        List<Entry> candidates = new ArrayList<>();
        for (Entry entry : mFormats) {
            if (entry.drc == drc && entry.itag.equals(itag)) {
                candidates.add(entry);
            }
        }

        if (candidates.size() > 1) {
            candidates = sameLanguage(candidates, label, language);
        }

        if (candidates.isEmpty()) {
            return mTrackDb;
        }
        float db = candidates.get(0).db;
        for (Entry entry : candidates) {
            if (Math.abs(entry.db - db) > SAME_DB) {
                return mTrackDb; // ambiguous
            }
        }
        return db;
    }

    @Nullable
    public Float getTrackDb() {
        return mTrackDb;
    }

    /**
     * The candidates in the playing track's language, or all of them when none is. The manifest's
     * label is the display string verbatim, so it alone tells "en (original)" from
     * "en (descriptive)"; the language code is the fallback for a format that has no label.
     */
    private static List<Entry> sameLanguage(List<Entry> candidates, @Nullable String label, @Nullable String language) {
        List<Entry> sameLabel = new ArrayList<>();
        List<Entry> sameCode = new ArrayList<>();
        for (Entry entry : candidates) {
            if (entry.language == null) {
                if (label == null && language == null) {
                    sameLabel.add(entry);
                }
            } else if (entry.language.equals(label)) {
                sameLabel.add(entry);
            } else if (code(entry.language).equalsIgnoreCase(language)) {
                sameCode.add(entry);
            }
        }
        return !sameLabel.isEmpty() ? sameLabel : !sameCode.isEmpty() ? sameCode : candidates;
    }

    /**
     * The manifest's lang: the display string minus its " (kind)" suffix, with the hyphen
     * YouTubeHelper.exoNameFix swapped in undone (the player lowercases it).
     */
    private static String code(String displayLanguage) {
        String code = displayLanguage;
        int mark = code.lastIndexOf(" (");
        if (mark > 0 && code.endsWith(")")) {
            code = code.substring(0, mark);
        }
        return code.replace('\u2010', '-');
    }

    private static boolean isUsable(@Nullable Float db) {
        return db != null && !db.isNaN() && !db.isInfinite();
    }
}
