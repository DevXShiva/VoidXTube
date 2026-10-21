package com.newtube.mobile.ui.playback;

import android.util.Base64;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * VOIDXDEV(lyrics): BitChord's lyrics sources (its data/lyrics package), the ones that answer without a
 * key today, each asked by {@link Lyrics} for one recording. Every one returns null on a miss.
 * <ul>
 *   <li>lrc.red and BiniLyrics: Apple Music's TTML (word or line timing), by ISRC or by name;</li>
 *   <li>BetterLyrics and its QQ Music "Portato" endpoint: answer only songs they have cached;</li>
 *   <li>PaxSenix: Apple's catalogue searched with the token of Apple's own web player;</li>
 *   <li>LyricsPlus (YouLy+, community mirrors), Unison (contributed), KuGou (strong outside English);</li>
 *   <li>Genius: plain text scraped from the song page, the last resort.</li>
 * </ul>
 * Left out, as of 2026-10: SimpMusic and Musixmatch (block this region), PaxSenix's Spotify and
 * Musixmatch routes (key), YouTube's transcript (refused), Megalobiz (site rebuilt). LRCLIB and
 * YouTube Music's own tab are in {@link Lyrics}.
 */
final class LyricsSources {
    private static final String[] LYRICS_PLUS = {
            "https://lyricsplus.binimum.org", "https://lyricsplus.prjktla.my.id", "https://lyricsplus.atomix.one",
            "https://lyricsplus.prjktla.workers.dev", "https://lyricsplus-seven.vercel.app",
            "https://lyrics-plus-backend.vercel.app"};
    /** Longest the lookup waits to learn which recording this is: a slow host must not hold it up. */
    private static final long IDENTIFY_MS = 2_500;
    /** KuGou's duration slack between its cut and ours. */
    private static final int KUGOU_SLACK_S = 8;

    private static final Pattern QRC_LINE = Pattern.compile("^\\[(\\d+),\\d+](.*)$");
    private static final Pattern QRC_WORD = Pattern.compile("\\(\\d+,\\d+\\)");
    private static final Pattern QRC_CONTENT = Pattern.compile("LyricContent=\"([^\"]*)\"");
    private static final Pattern APPLE_SCRIPT = Pattern.compile("/assets/index~[^\"]+\\.js");
    private static final Pattern JWT = Pattern.compile("eyJ[\\w-]+\\.eyJ[\\w-]+\\.[\\w-]+");
    private static final Pattern BRACKETS = Pattern.compile("\\s*[(\\[][^)\\]]*[)\\]]");
    private static final Pattern SECTION = Pattern.compile("^\\[[^\\]]*]$");
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos|nbsp);");
    /** A credit line some sources open with: "Lyrics by：...", "Composed by: ...". */
    private static final Pattern CREDIT = Pattern.compile(
            "(?i)^(?:lyrics|lyricist|written|composed|composer|music|produced|producer|arranged|arranger"
                    + "|mixed|mastered)(?:\\s+by)?\\s*[:：]");

    @Nullable
    private static volatile String sAppleToken;
    private static OkHttpClient sSlowClient;

    private LyricsSources() {
    }

    /** What every source is asked: the recording's name, artists, length (0 unknown) and ISRC. */
    static final class Query {
        final String title;
        final String artist;
        final int seconds;
        @Nullable
        final String isrc;

        Query(String title, String artist, int seconds, @Nullable String isrc) {
            this.title = title;
            this.artist = artist;
            this.seconds = seconds;
            this.isrc = isrc;
        }
    }

    /** A recording lrc.red knows: the name every source can be asked by, and its true length. */
    static final class Recording {
        final String isrc;
        final int seconds;

        Recording(String isrc, int seconds) {
            this.isrc = isrc;
            this.seconds = seconds;
        }
    }

    /**
     * BitChord's first step: which recording is this? One search on lrc.red, given up on after
     * {@link #IDENTIFY_MS}; null when it does not know the song.
     */
    @Nullable
    static Recording identify(String title, String artist, int videoSeconds) {
        Future<Recording> answer = Lyrics.POOL.submit(() -> {
            JSONArray hits = new JSONObject(get("https://lrc.red/search.json?q="
                    + enc(title + " " + firstArtist(artist)))).optJSONArray("hits");
            Recording best = null;
            double bestOff = Double.MAX_VALUE;
            for (int i = 0; hits != null && i < hits.length(); i++) {
                JSONObject hit = hits.optJSONObject(i);
                String isrc = hit != null ? hit.optString("isrc") : "";
                if (isrc.isEmpty() || !Lyrics.sameArtist(hit.optString("artist"), artist)
                        || !sameTitle(hit.optString("title"), title)) {
                    continue;
                }
                double duration = hit.optDouble("duration", 0);
                double off = videoSeconds > 0 ? Math.abs(duration - videoSeconds) : 0;
                if (off < bestOff) {
                    bestOff = off;
                    best = new Recording(isrc, (int) Math.round(duration));
                }
            }
            return best;
        });
        try {
            return answer.get(IDENTIFY_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            answer.cancel(true);
            return null;
        }
    }

    @Nullable
    static Lyrics lrcRed(Query query) throws Exception {
        if (query.isrc == null) {
            return null;
        }
        return enough(Lyrics.parseTtml(get("https://lrc.red/s/" + query.isrc + ".ttml"),
                query.title, query.artist, "lrc.red"));
    }

    /**
     * The same Apple catalogue matched by name: raced only when lrc.red had no ISRC to go on
     * ({@link Lyrics}), asked anyway when the listener picks it.
     */
    @Nullable
    static Lyrics biniLyrics(Query query) throws Exception {
        JSONArray results = new JSONObject(get("https://lyrics-api.binimum.org/?track=" + enc(query.title)
                + "&artist=" + enc(query.artist) + duration("&duration=", query))).optJSONArray("results");
        JSONObject hit = results != null ? results.optJSONObject(0) : null;
        String url = hit != null ? hit.optString("lyricsUrl") : "";
        return url.isEmpty() ? null : enough(Lyrics.parseTtml(get(url), query.title, query.artist, "BiniLyrics"));
    }

    @Nullable
    static Lyrics betterLyrics(Query query) throws Exception {
        String ttml = new JSONObject(get("https://lyrics-api.boidu.dev/getLyrics" + betterParams(query)))
                .optString("ttml");
        return ttml.isEmpty() ? null : enough(Lyrics.parseTtml(ttml, query.title, query.artist, "BetterLyrics"));
    }

    @Nullable
    static Lyrics betterLyricsPortato(Query query) throws Exception {
        String qrc = new JSONObject(get("https://lyrics-api.boidu.dev/qq/getLyrics" + betterParams(query)))
                .optString("lyrics");
        return qrc.isEmpty() ? null : enough(dropCredits(parseQrc(qrc, query.title, query.artist, "QQ Music")));
    }

    private static String betterParams(Query query) throws IOException {
        return "?s=" + enc(query.title) + "&a=" + enc(query.artist) + duration("&d=", query);
    }

    @Nullable
    static Lyrics paxSenix(Query query) throws Exception {
        String token = appleToken();
        Request search = new Request.Builder()
                .url("https://amp-api.music.apple.com/v1/catalog/us/search?term="
                        + enc(query.title + " " + firstArtist(query.artist)) + "&types=songs&limit=10&l=en-US")
                .header("User-Agent", Lyrics.AGENT)
                .header("Authorization", "Bearer " + token)
                .header("Origin", "https://music.apple.com")
                .header("Referer", "https://music.apple.com/")
                .build();
        String found;
        try {
            found = Lyrics.body(search);
        } catch (IOException e) {
            sAppleToken = null; // expired or refused: scraped again next time
            throw e;
        }
        JSONObject results = new JSONObject(found).optJSONObject("results");
        JSONObject songs = results != null ? results.optJSONObject("songs") : null;
        JSONArray data = songs != null ? songs.optJSONArray("data") : null;
        String id = null;
        double bestOff = Double.MAX_VALUE;
        for (int i = 0; data != null && i < data.length(); i++) {
            JSONObject song = data.optJSONObject(i);
            JSONObject about = song != null ? song.optJSONObject("attributes") : null;
            if (about == null || !Lyrics.sameArtist(about.optString("artistName"), query.artist)
                    || !sameTitle(about.optString("name"), query.title)) {
                continue;
            }
            double off = query.seconds > 0 ? Math.abs(about.optLong("durationInMillis") / 1000.0 - query.seconds) : 0;
            if (off < bestOff) {
                bestOff = off;
                id = song.optString("id");
            }
        }
        if (id == null || id.isEmpty()) {
            return null;
        }
        String ttml = new JSONObject(get("https://lyrics.paxsenix.org/apple-music/lyrics?id=" + enc(id) + "&ttml=true"))
                .optString("content");
        return ttml.isEmpty() ? null : enough(Lyrics.parseTtml(ttml, query.title, query.artist, "PaxSenix"));
    }

    /** The developer token Apple's own web player carries, found in its main script; kept for the session. */
    private static synchronized String appleToken() throws IOException {
        if (sAppleToken == null) {
            Matcher script = APPLE_SCRIPT.matcher(get("https://music.apple.com/us/new", "text/html"));
            if (!script.find()) {
                throw new IOException("no Apple script");
            }
            Matcher token = JWT.matcher(Lyrics.body(new Request.Builder()
                    .url("https://music.apple.com" + script.group())
                    .header("User-Agent", Lyrics.AGENT)
                    .build(), slowClient()));
            if (!token.find()) {
                throw new IOException("no Apple token");
            }
            sAppleToken = token.group();
        }
        return sAppleToken;
    }

    /** YouLy+'s backend: the first mirror that answers; most come and go. */
    @Nullable
    static Lyrics lyricsPlus(Query query) throws Exception {
        String path = "/v2/lyrics/get?title=" + enc(query.title) + "&artist=" + enc(query.artist)
                + duration("&duration=", query) + (query.isrc != null ? "&isrc=" + enc(query.isrc) : "");
        for (String host : LYRICS_PLUS) {
            JSONObject answer;
            try {
                answer = new JSONObject(get(host + path));
            } catch (Exception e) {
                continue; // down, moved, or a Cloudflare page instead of JSON
            }
            String type = answer.optString("type");
            JSONArray sung = answer.optJSONArray("lyrics");
            List<String> lines = new ArrayList<>();
            List<Long> times = new ArrayList<>();
            for (int i = 0; sung != null && i < sung.length(); i++) {
                JSONObject line = sung.optJSONObject(i);
                if (line == null) {
                    continue;
                }
                StringBuilder lead = new StringBuilder();
                StringBuilder backing = new StringBuilder();
                JSONArray syllables = line.optJSONArray("syllabus");
                for (int j = 0; syllables != null && j < syllables.length(); j++) {
                    JSONObject syllable = syllables.optJSONObject(j);
                    if (syllable != null) {
                        (syllable.optBoolean("isBackground") ? backing : lead).append(syllable.optString("text"));
                    }
                }
                String text = lead.length() > 0 ? lead.toString().trim() : line.optString("text").trim();
                String echo = backing.toString().trim();
                if (!echo.isEmpty()) {
                    text = (text + " " + (echo.startsWith("(") ? echo : "(" + echo + ")")).trim();
                }
                if (!text.isEmpty()) {
                    lines.add(text);
                    times.add(line.optLong("time"));
                }
            }
            boolean timed = "Word".equalsIgnoreCase(type) || "Line".equalsIgnoreCase(type)
                    || "Syllable".equalsIgnoreCase(type);
            return enough(new Lyrics(query.title, query.artist, lines, timed ? toArray(times) : null, "LyricsPlus"));
        }
        return null;
    }

    @Nullable
    static Lyrics unison(Query query) throws Exception {
        JSONObject answer = new JSONObject(get("https://unison.boidu.dev/lyrics?song=" + enc(query.title)
                + "&artist=" + enc(query.artist) + duration("&duration=", query)));
        JSONObject data = answer.optBoolean("success") ? answer.optJSONObject("data") : null;
        String text = data != null ? data.optString("lyrics") : "";
        if (text.isEmpty()) {
            return null;
        }
        Lyrics found = "ttml".equalsIgnoreCase(data.optString("format"))
                ? Lyrics.parseTtml(text, query.title, query.artist, "Unison")
                : Lyrics.parseLrc(text, query.title, query.artist, "Unison");
        return enough(found.lines.isEmpty() ? plain(text, query, "Unison") : found);
    }

    /** KuGou: its song search, then the lyric search for that song's hash, then the LRC itself. */
    @Nullable
    static Lyrics kuGou(Query query) throws Exception {
        String keyword = BRACKETS.matcher(query.title).replaceAll("").trim() + " - "
                + BRACKETS.matcher(firstArtist(query.artist)).replaceAll("").trim();
        JSONObject data = new JSONObject(get("https://mobileservice.kugou.com/api/v3/search/song?version=9108"
                + "&plat=0&pagesize=8&showtype=0&keyword=" + enc(keyword))).optJSONObject("data");
        JSONArray songs = data != null ? data.optJSONArray("info") : null;
        String hash = null;
        int bestOff = Integer.MAX_VALUE;
        for (int i = 0; songs != null && i < songs.length(); i++) {
            JSONObject song = songs.optJSONObject(i);
            if (song == null || song.optString("hash").isEmpty()) {
                continue;
            }
            int off = query.seconds > 0 ? Math.abs(song.optInt("duration") - query.seconds) : 0;
            if (off <= KUGOU_SLACK_S && off < bestOff) {
                bestOff = off;
                hash = song.optString("hash");
            }
        }
        if (hash == null) {
            return null;
        }
        JSONArray candidates = new JSONObject(get("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&hash="
                + enc(hash))).optJSONArray("candidates");
        JSONObject candidate = candidates != null ? candidates.optJSONObject(0) : null;
        if (candidate == null) {
            return null;
        }
        String content = new JSONObject(get("https://lyrics.kugou.com/download?fmt=lrc&charset=utf8&client=pc&ver=1"
                + "&id=" + enc(candidate.optString("id")) + "&accesskey=" + enc(candidate.optString("accesskey"))))
                .optString("content");
        if (content.isEmpty()) {
            return null;
        }
        String lrc = new String(Base64.decode(content, Base64.DEFAULT), StandardCharsets.UTF_8).replace("﻿", "");
        return enough(dropCredits(Lyrics.parseLrc(lrc, query.title, query.artist, "KuGou")));
    }

    /** Genius: its search, then the song page's lyrics containers, as plain text. */
    @Nullable
    static Lyrics genius(Query query) throws Exception {
        JSONObject answer = new JSONObject(get("https://genius.com/api/search/multi?q="
                + enc(firstArtist(query.artist) + " " + query.title), "text/html,application/json"));
        JSONObject response = answer.optJSONObject("response");
        JSONArray sections = response != null ? response.optJSONArray("sections") : null;
        for (int i = 0; sections != null && i < sections.length(); i++) {
            JSONObject section = sections.optJSONObject(i);
            JSONArray hits = section != null && "song".equals(section.optString("type"))
                    ? section.optJSONArray("hits") : null;
            for (int j = 0; hits != null && j < hits.length(); j++) {
                JSONObject hit = hits.optJSONObject(j);
                JSONObject song = hit != null ? hit.optJSONObject("result") : null;
                if (song != null && Lyrics.sameArtist(song.optString("artist_names"), query.artist)
                        && sameTitle(song.optString("title"), query.title) && !song.optString("url").isEmpty()) {
                    List<String> lines = geniusLines(get(song.optString("url"), "text/html"));
                    return enough(new Lyrics(query.title, query.artist, lines, null, "Genius"));
                }
            }
        }
        return null;
    }

    /** The words in a Genius page's lyrics containers, section headers ("[Chorus]") left out. */
    static List<String> geniusLines(String html) {
        List<String> lines = new ArrayList<>();
        int at = 0;
        while ((at = html.indexOf("data-lyrics-container=\"true\"", at)) >= 0) {
            int open = html.lastIndexOf("<div", at);
            int end = divEnd(html, open);
            if (open < 0 || end < 0) {
                break;
            }
            String inner = html.substring(html.indexOf('>', at) + 1, end - "</div>".length());
            int excluded;
            while ((excluded = inner.indexOf("data-exclude-from-selection=\"true\"")) >= 0) {
                int from = inner.lastIndexOf("<div", excluded);
                int to = divEnd(inner, from);
                if (from < 0 || to < 0) {
                    break;
                }
                inner = inner.substring(0, from) + inner.substring(to);
            }
            String text = unescape(inner.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]+>", ""));
            for (String line : text.split("\n")) {
                line = line.trim();
                boolean blank = line.isEmpty();
                if (SECTION.matcher(line).matches()
                        || (blank && (lines.isEmpty() || lines.get(lines.size() - 1).isEmpty()))) {
                    continue;
                }
                lines.add(line);
            }
            at = end;
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    /** Where the {@code <div} at {@code open} is closed (just past its {@code </div>}); -1 if never. */
    private static int divEnd(String html, int open) {
        if (open < 0) {
            return -1;
        }
        int depth = 0;
        int at = open;
        while (true) {
            int nextOpen = html.indexOf("<div", at);
            int nextClose = html.indexOf("</div", at);
            if (nextClose < 0) {
                return -1;
            }
            if (nextOpen >= 0 && nextOpen < nextClose) {
                depth++;
                at = nextOpen + 4;
            } else {
                depth--;
                at = html.indexOf('>', nextClose) + 1;
                if (depth == 0 || at == 0) {
                    return depth == 0 ? at : -1;
                }
            }
        }
    }

    static String unescape(String text) {
        Matcher entity = ENTITY.matcher(text);
        StringBuffer out = new StringBuffer();
        while (entity.find()) {
            String name = entity.group(1);
            String value;
            switch (name) {
                case "amp": value = "&"; break;
                case "lt": value = "<"; break;
                case "gt": value = ">"; break;
                case "quot": value = "\""; break;
                case "apos": value = "'"; break;
                case "nbsp": value = " "; break;
                default:
                    int code = name.startsWith("#x") ? Integer.parseInt(name.substring(2), 16)
                            : Integer.parseInt(name.substring(1));
                    value = new String(Character.toChars(code));
            }
            entity.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        entity.appendTail(out);
        return out.toString();
    }

    /** QQ Music's QRC: "[start,length]Word(start,length) (start,length)Word..." per line. */
    static Lyrics parseQrc(String qrc, String title, String artist, String source) {
        Matcher content = QRC_CONTENT.matcher(qrc);
        String text = content.find() ? unescape(content.group(1)) : qrc;
        List<String> lines = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        for (String raw : text.split("\n")) {
            Matcher line = QRC_LINE.matcher(raw.trim());
            if (line.matches()) {
                String words = QRC_WORD.matcher(line.group(2)).replaceAll("").trim();
                if (!words.isEmpty()) {
                    lines.add(words);
                    times.add(Long.parseLong(line.group(1)));
                }
            }
        }
        return new Lyrics(title, artist, lines, toArray(times), source);
    }

    /** Drops the title and credit lines some sources open with ("Song - Artist", "Lyrics by：..."). */
    static Lyrics dropCredits(Lyrics lyrics) {
        String title = Lyrics.letters(lyrics.title);
        int skip = 0;
        while (skip < lyrics.lines.size() && skip < 8) {
            String line = lyrics.lines.get(skip);
            boolean credit = line.indexOf('：') >= 0 || CREDIT.matcher(line).find()
                    || (line.contains(" - ") && !title.isEmpty() && Lyrics.letters(line).contains(title));
            if (!credit) {
                break;
            }
            skip++;
        }
        if (skip == 0) {
            return lyrics;
        }
        long[] times = lyrics.times;
        return new Lyrics(lyrics.title, lyrics.artist, new ArrayList<>(lyrics.lines.subList(skip, lyrics.lines.size())),
                times == null ? null : java.util.Arrays.copyOfRange(times, skip, times.length), lyrics.source);
    }

    /** Titles that name the same song once packaging is off: "Ooroda Oththa Don (From "Baththa")". */
    static boolean sameTitle(String theirs, String ours) {
        String a = Lyrics.letters(Lyrics.cleanTitle(theirs, ""));
        String b = Lyrics.letters(ours);
        return !a.isEmpty() && !b.isEmpty() && (a.contains(b) || b.contains(a));
    }

    private static Lyrics plain(String text, Query query, String source) {
        List<String> lines = new ArrayList<>();
        for (String line : text.trim().split("\n")) {
            lines.add(line.trim());
        }
        return new Lyrics(query.title, query.artist, lines, null, source);
    }

    @Nullable
    private static Lyrics enough(@Nullable Lyrics lyrics) {
        return lyrics != null && lyrics.lines.size() >= Lyrics.MIN_LINES ? lyrics : null;
    }

    private static long[] toArray(List<Long> times) {
        long[] array = new long[times.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = times.get(i);
        }
        return array;
    }

    private static String firstArtist(String artist) {
        return Lyrics.ARTISTS.split(artist)[0].trim();
    }

    private static String duration(String name, Query query) {
        return query.seconds > 0 ? name + query.seconds : "";
    }

    private static String enc(String text) throws IOException {
        return URLEncoder.encode(text, "UTF-8");
    }

    static String get(String url) throws IOException {
        return get(url, "application/json");
    }

    private static String get(String url, String accept) throws IOException {
        return Lyrics.body(new Request.Builder()
                .url(url)
                .header("User-Agent", Lyrics.AGENT)
                .header("Accept", accept)
                .build());
    }

    /** For Apple's 3 MB web-player script, which the lyrics deadline would cut off. */
    private static synchronized OkHttpClient slowClient() {
        if (sSlowClient == null) {
            sSlowClient = Lyrics.client().newBuilder().callTimeout(20, TimeUnit.SECONDS).build();
        }
        return sSlowClient;
    }
}
