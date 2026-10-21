package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertEquals;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.Locale;

/** NEWTUBE(audio-label): the names the app shows come from ICU, which reads like YouTube. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class AudioTrackLabelIcuTest {
    private static String icu(String raw, Locale ui, String dubbed) {
        return AudioTrackLabel.format(raw, ui, AudioTrackLabel.icuNames(ui), "original", dubbed,
                "auto-dubbed", "audio description");
    }

    @Test
    public void chineseScriptsReadSimplifiedAndTraditional() {
        assertEquals("Chinese (Simplified, dubbed)", icu("zh‐Hans (dubbed)", Locale.ENGLISH, "dubbed"));
        assertEquals("Chinese (Traditional, dubbed)", icu("zh‐Hant (dubbed)", Locale.ENGLISH, "dubbed"));
    }

    @Test
    public void regionsAndPlainLanguagesAreUnchanged() {
        assertEquals("English (United States, auto-dubbed)",
                icu("en‐US (dubbed-auto)", Locale.ENGLISH, "dubbed"));
        assertEquals("English (original)", icu("en (original)", Locale.ENGLISH, "dubbed"));
    }

    @Test
    public void namesFollowTheAppLanguage() {
        assertEquals("Chino (simplificado, doblado)", icu("zh‐Hans (dubbed)", new Locale("es"), "doblado"));
    }
}
