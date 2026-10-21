package com.newtube.mobile.ui.common;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * VOIDXDEV(card avatars, issue #6): channel avatar URLs for the feed cards. The feeds come from the
 * TV client, whose tiles carry no avatar, so cards look avatars up here: filled from the
 * subscribed-channels list, the watch page and channel search results, and kept across launches.
 * Keyed by channel id and by channel name (TV tiles often have no id until opened).
 * ponytail: one prefs entry per channel, never pruned; a few hundred short strings at most.
 */
public final class ChannelAvatars {
    private static final String PREFS = "voidx_channel_avatars";
    private static SharedPreferences sPrefs;

    public interface Callback {
        void onAvatar(String url);
    }

    /** Still on screen? Read on a worker thread: a stale answer only costs or saves one lookup. */
    public interface Wanted {
        boolean wanted();
    }

    /**
     * Unknown avatars are looked up off the critical path, newest first: a card asks once it is
     * bound and has stayed {@link #FETCH_DELAY_MS} (a fling's cards don't ask), the queue is LIFO so
     * the cards on screen go before ones scrolled past, a lookup nobody wants any more is dropped,
     * and cards of the same channel share one lookup. Found avatars are kept for good; a channel
     * with no match isn't asked again this run. The lookup is an anonymous MWEB channel search for
     * the id or the name (~12 KB; a channel page is ~70 KB), on a plain client: the shared one adds
     * the TV sign-in, which WEB endpoints refuse.
     */
    private static final long FETCH_DELAY_MS = 150;
    private static final ExecutorService sFetcher = new java.util.concurrent.ThreadPoolExecutor(6, 6,
            30, TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingDeque<Runnable>() {
                @Override
                public boolean offer(Runnable runnable) {
                    return offerFirst(runnable); // LIFO: the latest cards are the ones on screen
                }
            }, r -> {
                Thread thread = new Thread(() -> {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    r.run();
                }, "ChannelAvatars");
                thread.setDaemon(true);
                return thread;
            });
    /** Main thread only. */
    private static final java.util.Map<String, java.util.List<Object[]>> sPending = new java.util.HashMap<>();
    private static final Set<String> sMissing = new HashSet<>();
    private static final android.os.Handler sMain = new android.os.Handler(android.os.Looper.getMainLooper());
    private static OkHttpClient sHttp;

    /**
     * Main thread. {@code callback} runs on the main thread, only when an avatar was found.
     * {@code urgent}: no settle delay (the Shorts player shows one short at a time).
     */
    public static void fetch(Context context, @Nullable String channelId, @Nullable String name,
                             Wanted wanted, Callback callback, boolean urgent) {
        String id = channelId != null && channelId.startsWith("UC") ? channelId : null;
        String query = id != null ? id : (name != null ? name.trim() : "");
        if (query.isEmpty() || sMissing.contains(query)) {
            return;
        }
        java.util.List<Object[]> waiting = sPending.get(query);
        if (waiting != null) {
            waiting.add(new Object[] {wanted, callback}); // same channel: one lookup answers both
            return;
        }
        java.util.List<Object[]> list = new java.util.ArrayList<>();
        list.add(new Object[] {wanted, callback});
        sPending.put(query, list);
        Context app = context.getApplicationContext();
        sMain.postDelayed(() -> sFetcher.execute(() -> {
            boolean anyone = false;
            for (Object[] entry : new java.util.ArrayList<>(list)) {
                anyone |= ((Wanted) entry[0]).wanted();
            }
            String url = anyone ? lookup(query, id, name) : null;
            boolean asked = anyone;
            sMain.post(() -> {
                sPending.remove(query);
                if (url != null) {
                    put(app, channelId, name, url);
                    for (Object[] entry : list) {
                        ((Callback) entry[1]).onAvatar(url);
                    }
                } else if (asked) {
                    sMissing.add(query); // no such channel (or offline): not again this run
                }
            });
        }), urgent ? 0 : FETCH_DELAY_MS);
    }

    /**
     * The avatar of the channel search result whose id is {@code channelId} or, with no id (the
     * signed-in TV feeds give none), whose name is exactly {@code name}. Worker thread.
     */
    @Nullable
    private static String lookup(String query, @Nullable String channelId, @Nullable String name) {
        try {
            if (sHttp == null) {
                sHttp = new OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build();
            }
            org.json.JSONObject body = new org.json.JSONObject()
                    .put("context", new org.json.JSONObject().put("client", new org.json.JSONObject()
                            .put("clientName", "MWEB").put("clientVersion", "2.20251015.01.00").put("hl", "en")))
                    .put("query", query)
                    .put("params", "EgIQAg=="); // channels only
            Request request = new Request.Builder()
                    .url("https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
                    .post(RequestBody.create(body.toString(), MediaType.get("application/json")))
                    .build();
            try (Response response = sHttp.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return null;
                }
                return find(new org.json.JSONObject(response.body().string()), channelId, name);
            }
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static String find(Object node, @Nullable String channelId, @Nullable String name) {
        if (node instanceof org.json.JSONObject) {
            org.json.JSONObject object = (org.json.JSONObject) node;
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = object.opt(key);
                if (key.endsWith("hannelRenderer") && value instanceof org.json.JSONObject) {
                    String url = avatarIfMatch((org.json.JSONObject) value, channelId, name);
                    if (url != null) {
                        return url;
                    }
                } else {
                    String url = find(value, channelId, name);
                    if (url != null) {
                        return url;
                    }
                }
            }
        } else if (node instanceof org.json.JSONArray) {
            org.json.JSONArray array = (org.json.JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                String url = find(array.opt(i), channelId, name);
                if (url != null) {
                    return url;
                }
            }
        }
        return null;
    }

    @Nullable
    private static String avatarIfMatch(org.json.JSONObject channel, @Nullable String channelId, @Nullable String name) {
        boolean match;
        if (channelId != null) {
            match = channelId.equals(channel.optString("channelId"));
        } else {
            org.json.JSONObject title = channel.optJSONObject("title");
            String text = title == null ? "" : title.optString("simpleText", "");
            org.json.JSONArray runs = title != null ? title.optJSONArray("runs") : null;
            for (int i = 0; runs != null && i < runs.length(); i++) {
                text += runs.optJSONObject(i) != null ? runs.optJSONObject(i).optString("text") : "";
            }
            match = name != null && text.trim().equalsIgnoreCase(name.trim());
        }
        org.json.JSONObject thumbnail = channel.optJSONObject("thumbnail");
        org.json.JSONArray thumbnails = thumbnail != null ? thumbnail.optJSONArray("thumbnails") : null;
        if (!match || thumbnails == null || thumbnails.length() == 0) {
            return null;
        }
        String url = thumbnails.optJSONObject(thumbnails.length() - 1).optString("url", "");
        return url.startsWith("//") ? "https:" + url : (url.startsWith("http") ? url : null);
    }

    private ChannelAvatars() {
    }

    private static SharedPreferences prefs(Context context) {
        if (sPrefs == null) {
            sPrefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
        return sPrefs;
    }

    public static void put(Context context, @Nullable String channelId, @Nullable String name, @Nullable String url) {
        if (context == null || TextUtils.isEmpty(url) || !url.startsWith("http")) {
            return;
        }
        SharedPreferences.Editor editor = null;
        for (String key : new String[] {idKey(channelId), nameKey(name)}) {
            if (key != null && !url.equals(prefs(context).getString(key, null))) {
                if (editor == null) {
                    editor = prefs(context).edit();
                }
                editor.putString(key, url);
            }
        }
        if (editor != null) {
            editor.apply();
        }
    }

    @Nullable
    public static String get(Context context, @Nullable String channelId, @Nullable String name) {
        String key = idKey(channelId);
        String url = key != null ? prefs(context).getString(key, null) : null;
        if (url == null && (key = nameKey(name)) != null) {
            url = prefs(context).getString(key, null);
        }
        return url;
    }

    @Nullable
    private static String idKey(@Nullable String channelId) {
        return TextUtils.isEmpty(channelId) ? null : "id:" + channelId;
    }

    @Nullable
    private static String nameKey(@Nullable String name) {
        return TextUtils.isEmpty(name) ? null : "name:" + name.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Placeholder while an avatar loads: a frosted glass orb - a soft blur of a colour drawn from the
     * channel's name under a glass highlight and rim - breathing gently for its first seconds, so a
     * loading avatar reads as "coming", not as a letter badge.
     */
    public static Drawable placeholder(@Nullable String name) {
        return new OrbDrawable(name);
    }

    private static final class OrbDrawable extends Drawable {
        /** Breathes this long, then rests (a channel that never resolves shouldn't redraw forever). */
        private static final long PULSE_MS = 6_000;
        private static final long PERIOD_MS = 1_400;
        private final Paint mBlur = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mShine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float mHue;
        private final long mStart = android.os.SystemClock.uptimeMillis();
        private int mAlpha = 255;

        OrbDrawable(@Nullable String name) {
            mHue = Math.abs((name != null ? name.trim() : "").hashCode() % 360);
            mRim.setStyle(Paint.Style.STROKE);
        }

        @Override
        protected void onBoundsChange(@NonNull Rect b) {
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float r = Math.min(b.width(), b.height()) / 2f;
            // Two soft colour blobs, like a blurred photo behind frosted glass.
            int core = android.graphics.Color.HSVToColor(230, new float[] {mHue, 0.6f, 1f});
            int edge = android.graphics.Color.HSVToColor(120, new float[] {(mHue + 50) % 360, 0.55f, 0.6f});
            mBlur.setShader(new android.graphics.RadialGradient(cx - r * 0.3f, cy + r * 0.25f, r * 1.2f,
                    core, edge, android.graphics.Shader.TileMode.CLAMP));
            mGlow.setShader(new android.graphics.RadialGradient(cx + r * 0.4f, cy - r * 0.2f, r * 0.8f,
                    android.graphics.Color.HSVToColor(170, new float[] {(mHue + 70) % 360, 0.5f, 1f}),
                    0x00000000, android.graphics.Shader.TileMode.CLAMP));
            mShine.setShader(new android.graphics.RadialGradient(cx - r * 0.35f, cy - r * 0.45f, r * 0.7f,
                    0x80FFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP));
            mRim.setStrokeWidth(Math.max(1f, r * 0.06f));
            mRim.setColor(0x40FFFFFF);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            float r = Math.min(b.width(), b.height()) / 2f;
            long age = android.os.SystemClock.uptimeMillis() - mStart;
            float breath = age < PULSE_MS
                    ? 0.75f + 0.25f * (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * age / PERIOD_MS)) : 1f;
            int alpha = Math.round(mAlpha * breath);
            mBlur.setAlpha(alpha);
            mGlow.setAlpha(alpha);
            mShine.setAlpha(alpha);
            mRim.setAlpha(Math.round(0x40 * breath * mAlpha / 255f));
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r, mBlur);
            canvas.save();
            android.graphics.Path clip = new android.graphics.Path();
            clip.addCircle(b.exactCenterX(), b.exactCenterY(), r, android.graphics.Path.Direction.CW);
            canvas.clipPath(clip);
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r, mGlow);
            canvas.restore();
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r, mShine);
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r - mRim.getStrokeWidth() / 2, mRim);
            if (age < PULSE_MS && getCallback() != null) {
                invalidateSelf(); // next frame of the breath
            }
        }

        @Override
        public void setAlpha(int alpha) {
            mAlpha = alpha;
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            mBlur.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
