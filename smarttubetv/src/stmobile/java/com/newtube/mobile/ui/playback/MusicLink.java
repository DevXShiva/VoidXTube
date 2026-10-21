package com.newtube.mobile.ui.playback;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.core.util.Consumer;

import com.liskovsoft.sharedutils.okhttp.OkHttpManager;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * VOIDXDEV(music): whether a video has a YouTube Music page - a song, an art track, a music video or
 * a podcast episode. YouTube Music's own /player answers with {@code videoDetails.musicVideoType}
 * only for those; the field mask trims the answer to that one field (about 120 bytes).
 * ponytail: a fixed WEB_REMIX client version; bump it if YouTube ever starts refusing it (the pill
 * then just stays hidden).
 */
final class MusicLink {
    private static final String URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false";
    static final MediaType JSON = MediaType.get("application/json");
    /** The InnerTube context of YouTube Music's web client, shared with {@link Lyrics}. */
    static final String WEB_REMIX = "{\"client\":{\"clientName\":\"WEB_REMIX\","
            + "\"clientVersion\":\"1.20250922.03.00\",\"hl\":\"en\"}}";
    private static final Pattern MUSIC_TYPE = Pattern.compile("\"musicVideoType\"\\s*:\\s*\"MUSIC_VIDEO_TYPE_");
    /** Answers by video id; a video's catalog entry does not change while the app runs. */
    private static final Map<String, Boolean> sKnown = Collections.synchronizedMap(
            new LinkedHashMap<String, Boolean>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 200;
                }
            });
    private static OkHttpClient sClient;

    private MusicLink() {
    }

    /** {@code result} gets the answer on the main thread; false when unknown (no network, refused). */
    static void check(@NonNull String videoId, @NonNull Consumer<Boolean> result) {
        Boolean known = sKnown.get(videoId);
        if (known != null) {
            result.accept(known);
            return;
        }
        String body = "{\"context\":" + WEB_REMIX + ",\"videoId\":\"" + videoId + "\"}";
        Request request = new Request.Builder()
                .url(URL)
                .header("X-Goog-FieldMask", "videoDetails.musicVideoType")
                .post(RequestBody.create(body, JSON))
                .build();
        Handler main = new Handler(Looper.getMainLooper());
        client().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                main.post(() -> result.accept(false));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                boolean music = false;
                try (ResponseBody answer = response.body()) {
                    if (response.isSuccessful() && answer != null) {
                        music = isMusic(answer.string());
                        sKnown.put(videoId, music);
                    }
                } catch (IOException ignored) {
                    // unknown: not remembered, asked again next time
                }
                boolean found = music;
                main.post(() -> result.accept(found));
            }
        });
    }

    /** The answer names a music video type (the field mask leaves nothing else that could). */
    static boolean isMusic(String json) {
        return MUSIC_TYPE.matcher(json).find();
    }

    /**
     * The shared client's connections and HTTP/2, without its interceptors: those dress requests as
     * the app's own InnerTube client (and its sign-in), which YouTube Music's client would not match.
     */
    static synchronized OkHttpClient client() {
        if (sClient == null) {
            OkHttpClient.Builder builder = OkHttpManager.instance().getClient().newBuilder();
            builder.interceptors().clear();
            sClient = builder.build();
        }
        return sClient;
    }
}
