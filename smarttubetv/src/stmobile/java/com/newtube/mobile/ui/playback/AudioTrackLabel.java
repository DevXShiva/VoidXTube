package com.newtube.mobile.ui.playback;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NEWTUBE(audio-label): what the Audio track rows say. The track language arrives as a raw tag
 * built from the stream URL's xtags ("en (original)", "pt (dubbed)", "es (dubbed-auto)", sometimes
 * "en‐US" with a U+2010 hyphen), which the sheet used to show capitalized as "En (original)" /
 * "Pt (dubbed)". This turns it into the language's name in the app's UI language - "English
 * (original)", "Portuguese (dubbed)", "Spanish (auto-dubbed)" - for display only; selection and
 * persistence keep the raw tag.
 */
final class AudioTrackLabel {
    /** A BCP-47-ish tag, optionally followed by one parenthesized role: "en", "pt-BR (dubbed)". */
    private static final Pattern TAG = Pattern.compile(
            "^\\s*([a-zA-Z]{2,3}(?:[-_‐‑][a-zA-Z0-9]{2,8})*)\\s*(?:\\((.+)\\))?\\s*$");

    private AudioTrackLabel() {
    }

    static String format(Context context, String raw) {
        Locale uiLocale = context.getResources().getConfiguration().getLocales().get(0);
        return format(raw, uiLocale, icuNames(uiLocale),
                context.getString(R.string.mobile_audio_role_original),
                context.getString(R.string.mobile_audio_role_dubbed),
                context.getString(R.string.mobile_audio_role_auto_dubbed),
                context.getString(R.string.mobile_audio_role_descriptive));
    }

    /**
     * ICU's locale names, not {@link Locale#getDisplayName}: on Android the latter composes the
     * script's stand-alone name, "Chinese (Simplified Han)", where ICU (like YouTube) says
     * "Chinese (Simplified)".
     */
    static Function<Locale, String> icuNames(Locale uiLocale) {
        android.icu.text.LocaleDisplayNames names = android.icu.text.LocaleDisplayNames.getInstance(uiLocale);
        return names::localeDisplayName;
    }

    /** Pure form of {@link #format(Context, String)} with the JDK's locale names. */
    static String format(String raw, Locale uiLocale, String original, String dubbed,
                         String autoDubbed, String descriptive) {
        return format(raw, uiLocale, locale -> locale.getDisplayName(uiLocale),
                original, dubbed, autoDubbed, descriptive);
    }

    /** Returns {@code raw} when it is not a tag. */
    static String format(String raw, Locale uiLocale, Function<Locale, String> displayName,
                         String original, String dubbed, String autoDubbed, String descriptive) {
        if (raw == null) {
            return null;
        }
        Matcher m = TAG.matcher(raw);
        if (!m.matches()) {
            return raw;
        }
        String tag = m.group(1).replaceAll("[_‐‑]", "-");
        Locale language = Locale.forLanguageTag(tag);
        String name = displayName.apply(language);
        if (name == null) {
            return raw;
        }
        if (name.isEmpty() || name.equalsIgnoreCase(tag)) {
            return raw; // unknown to this device: the raw tag is more honest than a guess
        }
        name = name.substring(0, 1).toUpperCase(uiLocale) + name.substring(1);

        String role = m.group(2);
        if (role == null) {
            return name;
        }
        String roleKey = role.trim().toLowerCase(Locale.ROOT);
        // YouTube's acont values: original, dubbed, dubbed-auto (its own AI dub), descriptive, secondary.
        String roleLabel = roleKey.equals("original") ? original
                : roleKey.equals("dubbed") ? dubbed
                : roleKey.equals("dubbed-auto") ? autoDubbed
                : roleKey.equals("descriptive") ? descriptive
                : role.trim();
        // "Portuguese (Brazil)" + dubbed reads "Portuguese (Brazil, dubbed)", not two brackets.
        return name.endsWith(")")
                ? name.substring(0, name.length() - 1) + ", " + roleLabel + ")"
                : name + " (" + roleLabel + ")";
    }
}
