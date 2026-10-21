package com.newtube.mobile.ui.playback;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Consumer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.IOException;
import java.io.StringReader;
import java.net.URLEncoder;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * VOIDXDEV(lyrics): a song's lyrics, found the way BitChord finds them (github.com/kushagrasinghx/BitChord,
 * LyricsRepository). YouTube Music names the recording - its own title, artists and length for this
 * video - and lrc.red's search settles which recording that is (its ISRC and true length). Then every
 * source is asked at once and the answers are taken in BitChord's priority order, so a miss never
 * costs a round trip of its own and a faster, lower source never pre-empts a better one: the first
 * timed answer wins, else the first plain one. The sources are in {@link LyricsSources}.
 *
 * <p>Timing is only kept when it is this video's: an art track (the recording itself), or a video
 * the recording's length. A music video with an intro shows the words untimed - lit lines would run
 * ahead of the singing.
 *
 * <p>What each source answered is kept with the song ({@link Song}), so the listener can switch to
 * another provider's lyrics, as in BitChord (LyricsSheet's provider chooser).
 */
final class Lyrics {
    private static final String LRCLIB = "https://lrclib.net/api/search?track_name=";
    static final String AGENT = "VoidTube (https://github.com/DevXShiva/VoidXTube)";
    /** How far a recording's length may be from the video's for its timing to be this video's. */
    static final int SAME_LENGTH_S = 3;
    /** Fewer lines than this is a vandalised or stub entry ("probe"), not lyrics. */
    static final int MIN_LINES = 4;

    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern WORD_STAMP = Pattern.compile("<\\d{1,3}:\\d{2}[.:]\\d{1,3}>");
    static final Pattern ARTISTS = Pattern.compile(
            "\\s*(?:,|&|/|\\band\\b|\\bfeat\\.?|\\bft\\.?|\\bx\\b)\\s*", Pattern.CASE_INSENSITIVE);
    /** How an upload is labelled and who else is on it - never part of the name a lyrics site files. */
    private static final Pattern NOISE = Pattern.compile(
            "[(\\[]\\s*(?:from|feat|ft|featuring|with|official|lyric|lyrical|video|audio|full)\\b[^)\\]]*[)\\]]"
                    + "|\\s(?:feat|ft|featuring)\\.?\\s.*$"
                    + "|\\b(?:official\\s+)?(?:full\\s+)?(?:music|lyric(?:al)?|official)\\s+video(?:\\s+song)?\\b"
                    + "|\\b(?:full\\s+)?video\\s+song\\b"
                    + "|\\b(?:official\\s+audio|full\\s+(?:audio\\s+)?song|audio\\s+song|lyrics?|lyrical)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Every source by the name BitChord's chooser gives it, in the order {@link #find} asks them: the
     * first nine raced, PaxSenix and Genius only when those miss. {@link #ask} goes by this index.
     */
    static final String[] PROVIDERS = {"lrc.red", "BiniLyrics", "BetterLyrics", "BetterLyrics Portato",
            "LyricsPlus", "Unison", "YouTube Music", "KuGou", "LRCLIB", "PaxSenix", "Genius"};
    private static final int BINI = 1;
    private static final int PAXSENIX = 9;
    private static final int GENIUS = 10;
    /** A source's answer when it has no lyrics for the song. */
    private static final Lyrics MISS = new Lyrics("", "", Collections.emptyList(), null, "");

    static final ExecutorService POOL = Executors.newCachedThreadPool();
    /** Songs whose lyrics were found, by video id; a miss is asked again next time. */
    private static final Map<String, Song> sFound = Collections.synchronizedMap(
            new LinkedHashMap<String, Song>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Song> eldest) {
                    return size() > 20;
                }
            });
    private static OkHttpClient sClient;

    final String title;
    final String artist;
    final List<String> lines;
    /** Each line's start in ms, or null when these lyrics are not timed. */
    @Nullable
    final long[] times;
    final String source;

    Lyrics(String title, String artist, List<String> lines, @Nullable long[] times, String source) {
        this.title = title;
        this.artist = artist;
        this.lines = lines;
        this.times = times;
        this.source = source;
    }

    /** {@code result} gets the song, its lyrics found, on the main thread, or null when no source has them. */
    static void fetch(@NonNull String videoId, String fallbackTitle, String fallbackArtist,
                      @NonNull Consumer<Song> result) {
        Song known = sFound.get(videoId);
        if (known != null) {
            result.accept(known);
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        POOL.execute(() -> {
            Song found = null;
            try {
                found = find(videoId, fallbackTitle, fallbackArtist);
            } catch (Exception ignored) {
                // no network, refused, changed answers: no lyrics
            }
            if (found != null) {
                sFound.put(videoId, found);
            }
            Song answer = found;
            main.post(() -> result.accept(answer));
        });
    }

    /**
     * VOIDXDEV(lyrics): one provider's lyrics for {@code song}, the listener's pick in the provider
     * chooser. {@code result} gets them on the main thread (timed as {@link Song#lyrics} times them),
     * or null when that provider has none; the answer is kept with the song either way.
     */
    static void fetch(@NonNull Song song, int provider, @NonNull Consumer<Lyrics> result) {
        Handler main = new Handler(Looper.getMainLooper());
        POOL.execute(() -> {
            Lyrics found = null;
            try {
                found = ask(song, provider);
            } catch (Exception ignored) {
                // no network, refused, changed answers: none from this provider
            }
            Lyrics answer = found;
            main.post(() -> {
                song.answer(provider, answer);
                result.accept(song.lyrics(provider));
            });
        });
    }

    @Nullable
    private static Song find(String videoId, String fallbackTitle, String fallbackArtist) {
        Track track = null;
        try {
            track = track(videoId);
        } catch (IOException | JSONException ignored) {
            // the video's own title and channel stand in
        }
        String artist = track != null ? track.artist : fallbackArtist.replaceFirst(" - Topic$", "");
        String title = cleanTitle(track != null ? track.title : fallbackTitle, artist);
        int videoSeconds = track != null ? track.seconds : 0;
        LyricsSources.Recording recording = LyricsSources.identify(title, artist, videoSeconds);
        LyricsSources.Query query = new LyricsSources.Query(title, artist,
                recording != null ? recording.seconds : videoSeconds, recording != null ? recording.isrc : null);
        boolean timedOk = (track != null && track.audioTrack) || (recording != null && videoSeconds > 0
                && Math.abs(recording.seconds - videoSeconds) <= SAME_LENGTH_S);
        String lyricsId = track != null ? track.lyricsId : null;
        Song song = new Song(query, timedOk, lyricsId);

        // BitChord's order, minus the sources that are gone or want a key (see LyricsSources)
        List<Future<Lyrics>> racing = new ArrayList<>();
        for (int i = 0; i < PAXSENIX; i++) {
            int provider = i;
            // BiniLyrics is lrc.red's catalogue matched by name: raced only when lrc.red had no ISRC
            racing.add(i == BINI && query.isrc != null ? null : POOL.submit(() -> ask(song, provider)));
        }
        try {
            int plain = -1;
            for (int i = 0; i < racing.size(); i++) {
                Lyrics found = racing.get(i) != null ? song.answer(i, await(racing.get(i))) : null;
                if (found == null) {
                    continue;
                }
                if (timedOk && found.times != null) {
                    return song.show(i);
                }
                if (plain < 0) {
                    plain = i;
                }
                if (!timedOk) {
                    return song.show(plain); // nothing further down can do better than words
                }
            }
            // Asked only now, not raced: PaxSenix fetches Apple's 3 MB web player for a token, and
            // Genius scrapes a page - BitChord too keeps Genius for when everything else missed.
            if (timedOk || plain < 0) {
                Lyrics apple = song.answer(PAXSENIX, await(POOL.submit(() -> ask(song, PAXSENIX))));
                if (apple != null && (apple.times != null || plain < 0)) {
                    return song.show(PAXSENIX);
                }
            }
            if (plain >= 0) {
                return song.show(plain);
            }
            Lyrics genius = song.answer(GENIUS, await(POOL.submit(() -> ask(song, GENIUS))));
            return genius != null ? song.show(GENIUS) : null;
        } finally {
            for (int i = 0; i < racing.size(); i++) {
                Future<Lyrics> answer = racing.get(i);
                if (answer == null) {
                    continue;
                }
                // an answer already in is kept for the chooser; one still coming is called off
                if (answer.isDone() && !song.asked(i)) {
                    song.answer(i, await(answer));
                }
                answer.cancel(true);
            }
        }
    }

    /** Asks one source ({@link #PROVIDERS} index) for the song's lyrics; null when it has none. */
    @Nullable
    private static Lyrics ask(Song song, int provider) throws Exception {
        LyricsSources.Query query = song.query;
        switch (provider) {
            case 0: return LyricsSources.lrcRed(query);
            case BINI: return LyricsSources.biniLyrics(query);
            case 2: return LyricsSources.betterLyrics(query);
            case 3: return LyricsSources.betterLyricsPortato(query);
            case 4: return LyricsSources.lyricsPlus(query);
            case 5: return LyricsSources.unison(query);
            case 6: return song.lyricsId != null ? youtubeMusic(song.lyricsId, query) : null;
            case 7: return LyricsSources.kuGou(query);
            case 8: return lrclib(query);
            case PAXSENIX: return LyricsSources.paxSenix(query);
            default: return LyricsSources.genius(query);
        }
    }

    /**
     * VOIDXDEV(lyrics): one video's song as the sources were asked about it - what they are asked,
     * whether timing can be this video's, and each one's answer so far, so the provider chooser
     * shows what each has and switches without asking again. Filled on the lookup's thread before it
     * is handed on; touched only on the main thread after that.
     */
    static final class Song {
        final LyricsSources.Query query;
        final boolean timedOk;
        @Nullable
        final String lyricsId;
        /** By provider index: the source's lyrics, {@link Lyrics#MISS}, or null while never asked. */
        final Lyrics[] answers = new Lyrics[PROVIDERS.length];
        /** The source whose lyrics are shown. */
        int shown;

        Song(LyricsSources.Query query, boolean timedOk, @Nullable String lyricsId) {
            this.query = query;
            this.timedOk = timedOk;
            this.lyricsId = lyricsId;
        }

        boolean asked(int provider) {
            return answers[provider] != null;
        }

        /** {@code provider}'s lyrics, their timing kept only when it is this video's; null when none. */
        @Nullable
        Lyrics lyrics(int provider) {
            Lyrics answer = answers[provider];
            return answer == null || answer == MISS ? null : timedOk ? answer : answer.untimed();
        }

        /** Keeps {@code provider}'s answer, and hands it back. */
        @Nullable
        Lyrics answer(int provider, @Nullable Lyrics lyrics) {
            answers[provider] = lyrics != null ? lyrics : MISS;
            return lyrics;
        }

        Song show(int provider) {
            shown = provider;
            return this;
        }
    }

    /** These lyrics without their timing. */
    Lyrics untimed() {
        return times == null ? this : new Lyrics(title, artist, lines, null, source);
    }

    @Nullable
    static Lyrics await(@Nullable Future<Lyrics> answer) {
        try {
            return answer != null ? answer.get() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** What YouTube Music calls this video, and where its Lyrics tab is (null when it has none). */
    private static final class Track {
        String title;
        String artist;
        int seconds;
        /** An art track: the recording itself, so a recording's timing is this video's. */
        boolean audioTrack;
        @Nullable
        String lyricsId;
    }

    @Nullable
    private static Track track(String videoId) throws IOException, JSONException {
        JSONObject root = new JSONObject(music("next", "\"videoId\":\"" + videoId + "\""));
        List<JSONObject> found = new ArrayList<>();
        findAll(root, "playlistPanelVideoRenderer", found);
        Track track = null;
        for (JSONObject item : found) {
            if (videoId.equals(item.optString("videoId"))) {
                track = new Track();
                track.title = text(item.optJSONObject("title"));
                // "Artist, Artist & Artist • album or views • year or likes"
                track.artist = text(item.optJSONObject("longBylineText")).split(" \u2022 ")[0].trim();
                track.seconds = seconds(text(item.optJSONObject("lengthText")));
                track.audioTrack = item.toString().contains("MUSIC_VIDEO_TYPE_ATV");
                break;
            }
        }
        if (track == null || track.title.isEmpty()) {
            return null;
        }
        found.clear();
        findAll(root, "tabRenderer", found);
        for (JSONObject tab : found) {
            JSONObject endpoint = tab.optJSONObject("endpoint");
            JSONObject browse = endpoint != null ? endpoint.optJSONObject("browseEndpoint") : null;
            // "unselectable" is how YouTube Music greys the tab out for a song it has no lyrics for
            if (browse != null && !tab.optBoolean("unselectable")
                    && browse.toString().contains("MUSIC_PAGE_TYPE_TRACK_LYRICS")) {
                track.lyricsId = browse.optString("browseId", null);
            }
        }
        return track;
    }

    @Nullable
    private static Lyrics youtubeMusic(String browseId, LyricsSources.Query query) throws IOException, JSONException {
        List<JSONObject> found = new ArrayList<>();
        findAll(new JSONObject(music("browse", "\"browseId\":\"" + browseId + "\"")), "musicDescriptionShelfRenderer", found);
        if (found.isEmpty()) {
            return null;
        }
        String text = text(found.get(0).optJSONObject("description")).trim();
        String source = text(found.get(0).optJSONObject("footer")).replaceFirst("^Source:\\s*", "");
        List<String> lines = new ArrayList<>();
        Collections.addAll(lines, text.split("\n"));
        return lines.size() < MIN_LINES ? null
                : new Lyrics(query.title, query.artist, lines, null, source.isEmpty() ? "YouTube Music" : source);
    }

    @Nullable
    private static Lyrics lrclib(LyricsSources.Query query) throws IOException, JSONException {
        String title = query.title;
        String artist = query.artist;
        int seconds = query.seconds;
        JSONArray answer = new JSONArray(LyricsSources.get(LRCLIB + URLEncoder.encode(title, "UTF-8")));
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < answer.length(); i++) {
            JSONObject hit = answer.optJSONObject(i);
            if (hit != null) {
                hits.add(new Hit(hit.optString("artistName"), hit.optDouble("duration", 0),
                        hit.isNull("syncedLyrics") ? null : hit.optString("syncedLyrics"),
                        hit.isNull("plainLyrics") ? null : hit.optString("plainLyrics")));
            }
        }
        Hit hit = pick(hits, artist, seconds);
        if (hit == null) {
            return null;
        }
        if (timed(hit, seconds)) {
            return parseLrc(hit.synced, title, artist, "LRCLIB");
        }
        String plain = hit.plain != null ? hit.plain : hit.synced;
        List<String> lines = new ArrayList<>();
        for (String line : plain.trim().split("\n")) {
            lines.add(WORD_STAMP.matcher(STAMP.matcher(line).replaceAll("")).replaceAll("").trim());
        }
        return new Lyrics(title, artist, lines, null, "LRCLIB");
    }

    /** One LRCLIB search answer. */
    static final class Hit {
        final String artist;
        final double duration;
        @Nullable
        final String synced;
        @Nullable
        final String plain;

        Hit(String artist, double duration, @Nullable String synced, @Nullable String plain) {
            this.artist = artist;
            this.duration = duration;
            this.synced = synced;
            this.plain = plain;
        }
    }

    /**
     * The hit by one of this song's artists, timed to this video's length if one is, else the nearest
     * length; null when none is. A hit by somebody else is a different song with the same name.
     */
    @Nullable
    static Hit pick(List<Hit> hits, String artist, int seconds) {
        Hit best = null;
        for (Hit hit : hits) {
            String text = hit.plain != null ? hit.plain : hit.synced;
            if (text == null || text.trim().split("\n").length < MIN_LINES
                    || (!artist.isEmpty() && !sameArtist(hit.artist, artist))) {
                continue;
            }
            if (best == null || rank(hit, seconds) < rank(best, seconds)) {
                best = hit;
            }
        }
        return best;
    }

    /** Lower is better: a timed match first, then the nearest length. */
    private static double rank(Hit hit, int seconds) {
        return (timed(hit, seconds) ? 0 : 100_000) + (seconds > 0 ? Math.abs(hit.duration - seconds) : 0);
    }

    static boolean timed(Hit hit, int seconds) {
        return hit.synced != null && seconds > 0 && Math.abs(hit.duration - seconds) <= SAME_LENGTH_S;
    }

    /** Any one name in common: "Sai Abhyankkar" is on "Thamizh Aadhavan, ... & Sai Abhyankkar". */
    static boolean sameArtist(String theirs, String ours) {
        String all = letters(ours);
        for (String name : ARTISTS.split(theirs)) {
            String one = letters(name);
            if (!one.isEmpty() && all.contains(one)) {
                return true;
            }
        }
        String first = letters(ARTISTS.split(ours)[0]);
        return !first.isEmpty() && letters(theirs).contains(first);
    }

    static String letters(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /**
     * A YouTube title as a lyrics site files it: "Ooroda Oththa Don Video Song | Baththa | ..." is
     * "Ooroda Oththa Don", "Rick Astley - Never Gonna Give You Up (Official Video)" is "Never Gonna
     * Give You Up". "(Remix)", "(Live)" and the like stay: they name a different recording.
     */
    static String cleanTitle(String title, String artist) {
        String name = title;
        int bar = name.indexOf(" | ");
        if (bar > 0) {
            name = name.substring(0, bar);
        }
        name = NOISE.matcher(name).replaceAll(" ");
        int dash = name.indexOf(" - ");
        if (dash > 0 && !artist.isEmpty() && sameArtist(name.substring(0, dash), artist)) {
            name = name.substring(dash + 3);
        }
        name = name.replaceAll("\\s+", " ").trim().replaceAll("[\\s\\-\u2013\u2014,:]+$", "");
        return name.isEmpty() ? title.trim() : name;
    }

    /** "[mm:ss.xx] words" lines, by time; a line under several stamps (a chorus) is at each of them. */
    static Lyrics parseLrc(String lrc, String title, String artist, String source) {
        List<Map.Entry<Long, String>> timed = new ArrayList<>();
        for (String raw : lrc.split("\n")) {
            Matcher stamp = STAMP.matcher(raw);
            List<Long> at = new ArrayList<>();
            int end = 0;
            while (stamp.find() && stamp.start() == end) {
                at.add(ms(stamp));
                end = stamp.end();
            }
            String text = WORD_STAMP.matcher(raw.substring(end)).replaceAll("").trim();
            if (text.isEmpty()) {
                continue; // a metadata tag, or a stamp that only marks a pause
            }
            for (long time : at) {
                timed.add(new AbstractMap.SimpleEntry<>(time, text));
            }
        }
        Collections.sort(timed, (a, b) -> Long.compare(a.getKey(), b.getKey()));
        List<String> lines = new ArrayList<>();
        long[] times = new long[timed.size()];
        for (int i = 0; i < times.length; i++) {
            times[i] = timed.get(i).getKey();
            lines.add(timed.get(i).getValue());
        }
        return new Lyrics(title, artist, lines, times, source);
    }

    /**
     * Apple Music's TTML, a {@code <p begin>} per sung line: its words, the answering vocal
     * (ttm:role x-bg) after them in brackets, translations and romanisations left out. Read with DOM
     * (not namespace-aware) so it is the same on the JVM and on the phone, whose Expat-backed parser
     * files a prefixed attribute under its local name and refuses features it lacks.
     */
    static Lyrics parseTtml(String ttml, String title, String artist, String source) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        harden(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        harden(factory, "http://xml.org/sax/features/external-general-entities", false);
        harden(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        factory.setExpandEntityReferences(false);
        NodeList paragraphs = factory.newDocumentBuilder().parse(new InputSource(new StringReader(ttml)))
                .getElementsByTagName("p");
        List<Map.Entry<Long, String>> sung = new ArrayList<>();
        boolean timed = true;
        for (int i = 0; i < paragraphs.getLength(); i++) {
            Element paragraph = (Element) paragraphs.item(i);
            StringBuilder lead = new StringBuilder();
            StringBuilder backing = new StringBuilder();
            collect(paragraph, lead, backing, false);
            String text = squash(lead);
            String echo = squash(backing);
            if (!echo.isEmpty()) {
                text = (text + " " + (echo.startsWith("(") ? echo : "(" + echo + ")")).trim();
            }
            if (text.isEmpty()) {
                continue;
            }
            long begin = ttmlTime(paragraph.getAttribute("begin"));
            timed &= begin >= 0;
            sung.add(new AbstractMap.SimpleEntry<>(begin, text));
        }
        if (timed) {
            Collections.sort(sung, (a, b) -> Long.compare(a.getKey(), b.getKey()));
        }
        List<String> lines = new ArrayList<>();
        long[] times = new long[sung.size()];
        for (int i = 0; i < times.length; i++) {
            times[i] = sung.get(i).getKey();
            lines.add(sung.get(i).getValue());
        }
        return new Lyrics(title, artist, lines, timed ? times : null, source);
    }

    private static void collect(Node node, StringBuilder lead, StringBuilder backing, boolean inBacking) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                String role = attribute((Element) child, "ttm:role");
                if (!"x-translation".equals(role) && !"x-roman".equals(role)) {
                    collect(child, lead, backing, inBacking || "x-bg".equals(role));
                }
            } else if (child.getNodeType() == Node.TEXT_NODE) {
                (inBacking ? backing : lead).append(child.getNodeValue());
            }
        }
    }

    /** A prefixed attribute: the JVM keeps "ttm:role" whole, Android files it as "role". */
    private static String attribute(Element element, String name) {
        String value = element.getAttribute(name);
        if (!value.isEmpty()) {
            return value;
        }
        String local = name.substring(name.indexOf(':') + 1);
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; attributes != null && i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            if (name.equals(attribute.getNodeName()) || local.equals(attribute.getNodeName())
                    || local.equals(attribute.getLocalName())) {
                return attribute.getNodeValue();
            }
        }
        return "";
    }

    private static String squash(CharSequence text) {
        return text.toString().replaceAll("\\s+", " ").trim();
    }

    /** "12.345", "1:02.345", "00:01:02.345", "62.3s" or "62345ms" in ms; -1 when absent or unreadable. */
    static long ttmlTime(@Nullable String value) {
        if (value == null || value.trim().isEmpty()) {
            return -1;
        }
        String raw = value.trim();
        try {
            if (raw.endsWith("ms")) {
                return (long) Double.parseDouble(raw.substring(0, raw.length() - 2));
            }
            double seconds = 0;
            for (String part : (raw.endsWith("s") ? raw.substring(0, raw.length() - 1) : raw).split(":")) {
                seconds = seconds * 60 + Double.parseDouble(part);
            }
            return Math.round(seconds * 1000);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** A parser feature, where this parser has it (Android's refuses the Apache one outright). */
    private static void harden(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception ignored) {
            // not this parser's
        }
    }

    private static long ms(Matcher stamp) {
        String fraction = stamp.group(3);
        long ms = Long.parseLong(stamp.group(1)) * 60_000 + Long.parseLong(stamp.group(2)) * 1_000;
        if (fraction != null) {
            ms += Long.parseLong(fraction) * (fraction.length() == 1 ? 100 : fraction.length() == 2 ? 10 : 1);
        }
        return ms;
    }

    /** "3:46" or "1:02:03" in seconds; 0 when unreadable. */
    static int seconds(String length) {
        int seconds = 0;
        for (String part : length.trim().split(":")) {
            try {
                seconds = seconds * 60 + Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return seconds;
    }

    static String music(String endpoint, String fields) throws IOException {
        return body(new Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/" + endpoint + "?prettyPrint=false")
                .post(RequestBody.create("{\"context\":" + MusicLink.WEB_REMIX + "," + fields + "}", MusicLink.JSON))
                .build());
    }

    static String body(Request request) throws IOException {
        return body(request, client());
    }

    static String body(Request request, OkHttpClient client) throws IOException {
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IOException("HTTP " + response.code());
            }
            return body.string();
        }
    }

    /** {@link MusicLink}'s client, with a deadline: lyrics that come after the second chorus are no use. */
    static synchronized OkHttpClient client() {
        if (sClient == null) {
            sClient = MusicLink.client().newBuilder().callTimeout(6, TimeUnit.SECONDS).build();
        }
        return sClient;
    }

    static void findAll(@Nullable Object node, String key, List<JSONObject> out) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            for (Iterator<String> keys = object.keys(); keys.hasNext(); ) {
                String name = keys.next();
                Object value = object.opt(name);
                if (name.equals(key) && value instanceof JSONObject) {
                    out.add((JSONObject) value);
                }
                findAll(value, key, out);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                findAll(array.opt(i), key, out);
            }
        }
    }

    /** An InnerTube text: its runs joined, or its simpleText. */
    static String text(@Nullable JSONObject text) {
        if (text == null) {
            return "";
        }
        JSONArray runs = text.optJSONArray("runs");
        if (runs == null) {
            return text.optString("simpleText");
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run != null) {
                joined.append(run.optString("text"));
            }
        }
        return joined.toString();
    }
}
