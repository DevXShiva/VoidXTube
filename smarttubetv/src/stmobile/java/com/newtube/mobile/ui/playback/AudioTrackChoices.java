package com.newtube.mobile.ui.playback;

import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorUtil;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.MediaTrack;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * NEWTUBE(audio-track): the rows of the player's Audio track sheet, YouTube-style: one row per
 * language variant the video ships ("en (original)", "es (dubbed)", "fr (dubbed-auto)"), never one
 * per codec or bitrate. Every variant arrives as several tracks (AAC 139/140, Opus 249/250/251),
 * so a row stands for the variant and carries the one track a tap should select.
 *
 * <p>Which track: the one most like the track playing now - same codec family first, so switching
 * language does not also switch AAC and Opus under the listener, then the same DRC-ness (YouTube's
 * "stable volume" {@code -drc} variants) and channel count (plain stereo when nothing plays yet) -
 * and among those the highest bitrate, as the original default does
 * ({@code Media3TrackAdapter.applyOriginalAudioDefault}). A tap stores the track as the audio
 * preference, so this also decides what later videos are pinned to. The check sits on the variant
 * that is actually playing ({@link FormatItem#isSelected()} comes from the player's current track
 * selection, not from the stored preference).</p>
 */
final class AudioTrackChoices {

    static final class Choice {
        /** The variant's raw tag as the track reports it; {@code null} when the track has none. */
        @Nullable
        final String language;
        /** What the row says ("English (original)"). */
        final String label;
        /** The track a tap selects. */
        final FormatItem item;
        /** This variant is the one playing. */
        final boolean selected;

        Choice(@Nullable String language, String label, FormatItem item, boolean selected) {
            this.language = language;
            this.label = label;
            this.item = item;
            this.selected = selected;
        }
    }

    private AudioTrackChoices() {
    }

    /**
     * One {@link Choice} per language variant, sorted by label (the order YouTube's own list uses).
     *
     * @param labeler raw tag -> row label; receives {@code null} for an untagged track
     */
    static List<Choice> from(@Nullable List<FormatItem> audioFormats,
                             Function<String, String> labeler, Locale locale) {
        List<Choice> result = new ArrayList<>();
        if (audioFormats == null || audioFormats.isEmpty()) {
            return result;
        }

        FormatItem playing = null;
        for (FormatItem item : audioFormats) {
            if (item != null && item.isSelected()) {
                playing = item;
                break;
            }
        }

        // LinkedHashMap takes the null key: an untagged track is a variant of its own.
        Map<String, FormatItem> best = new LinkedHashMap<>();
        Map<String, Boolean> selected = new LinkedHashMap<>();
        for (FormatItem item : audioFormats) {
            if (item == null) {
                continue;
            }
            // "" and null are the same untagged variant (both would read "Default").
            String language = item.getLanguage() == null || item.getLanguage().isEmpty()
                    ? null : item.getLanguage();
            FormatItem current = best.get(language);
            if (current == null || isBetter(item, current, playing)) {
                best.put(language, item);
            }
            selected.put(language, Boolean.TRUE.equals(selected.get(language)) || item.isSelected());
        }

        for (Map.Entry<String, FormatItem> entry : best.entrySet()) {
            String language = entry.getKey();
            result.add(new Choice(language, labeler.apply(language), entry.getValue(),
                    Boolean.TRUE.equals(selected.get(language))));
        }

        Collator collator = Collator.getInstance(locale);
        Collections.sort(result, (a, b) -> collator.compare(a.label, b.label));
        return result;
    }

    /** The row with the check, or {@code null} when nothing is playing yet. */
    @Nullable
    static Choice selected(List<Choice> choices) {
        for (Choice choice : choices) {
            if (choice.selected) {
                return choice;
            }
        }
        return null;
    }

    private static boolean isBetter(FormatItem candidate, FormatItem current, @Nullable FormatItem playing) {
        int candidateAffinity = affinity(candidate, playing);
        int currentAffinity = affinity(current, playing);
        if (candidateAffinity != currentAffinity) {
            return candidateAffinity > currentAffinity;
        }
        return bitrate(candidate) > bitrate(current);
    }

    /**
     * How much a track looks like the one playing, most important first: same codec family (4),
     * same DRC-ness (2), same channel count (1). With nothing playing: plain stereo-or-unknown.
     */
    private static int affinity(FormatItem item, @Nullable FormatItem playing) {
        if (playing == null) {
            return (isDrc(item) ? 0 : 2) + (channels(item) > 2 ? 0 : 1);
        }
        int affinity = 0;
        String playingCodec = codecFamily(playing);
        if (playingCodec != null && playingCodec.equals(codecFamily(item))) {
            affinity += 4;
        }
        if (isDrc(playing) == isDrc(item)) {
            affinity += 2;
        }
        if (channels(playing) == channels(item)) {
            affinity += 1;
        }
        return affinity;
    }

    private static int channels(FormatItem item) {
        MediaTrack track = item.getTrack();
        return track != null && track.format != null ? track.format.channelCount : -1;
    }

    @Nullable
    private static String codecFamily(FormatItem item) {
        MediaTrack track = item.getTrack();
        String codecs = track != null && track.format != null ? track.format.codecs : null;
        return TrackSelectorUtil.codecNameShort(codecs);
    }

    private static boolean isDrc(FormatItem item) {
        MediaTrack track = item.getTrack();
        return track != null && TrackSelectorUtil.isDrc(track.format);
    }

    private static int bitrate(FormatItem item) {
        MediaTrack track = item.getTrack();
        return track != null && track.format != null ? track.format.bitrate : -1;
    }
}
