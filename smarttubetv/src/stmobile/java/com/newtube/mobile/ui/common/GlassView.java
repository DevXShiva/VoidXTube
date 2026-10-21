package com.newtube.mobile.ui.common;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlendMode;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.Random;

/**
 * VOIDXDEV(glass): iOS-style liquid glass over a {@link GlassSource} - BitChord's floating bar,
 * ported from Compose to plain RenderNodes, with its numbers:
 *
 * <ol>
 *   <li>API 31+: the source's live RenderNode, saturated 1.5x and lightly blurred (8dp), so what
 *       is under the glass stays readable and the lens below visibly bends it;</li>
 *   <li>API 33+: Kyant's backdrop lens - a circle-profile refraction 24dp deep along the rim with
 *       a depth bulge and seven-band chromatic dispersion;</li>
 *   <li>a #121212 tint at 40%, fine grain, and the specular rim: white at 50%, additive, lit along
 *       a 45 degree normal.</li>
 * </ol>
 *
 * Below API 31 there is nothing to sample, so the glass falls back to a near-opaque tint.
 * Children draw on top, unrefracted. Wire it with {@link #setSource}.
 */
public class GlassView extends FrameLayout {
    private static final float BLUR_DP = 8f;
    private static final float SATURATION = 1.5f;
    private static final float REFRACTION_HEIGHT_DP = 24f;
    private static final float REFRACTION_AMOUNT_DP = 24f;
    private static final float HIGHLIGHT_ANGLE = 45f;
    private static final int NOISE_ALPHA = 10; // ~4% grain
    private static final int FALLBACK_ALPHA = 0xEB;

    /*
     * The two shaders below are Kyant's backdrop (github.com/Kyant0/backdrop v2.0.0,
     * Copyright 2025 Kyant, Apache License 2.0), as vendored by BitChord; see THIRD_PARTY_NOTICES.
     */
    private static final String ROUNDED_RECT_SDF =
            "float sdRoundedRect(float2 coord, float2 halfSize, float radius) {\n"
            + "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n"
            + "    float outside = length(max(cornerCoord, 0.0)) - radius;\n"
            + "    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);\n"
            + "    return outside + inside;\n"
            + "}\n"
            + "float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {\n"
            + "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n"
            + "    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {\n"
            + "        return sign(coord) * normalize(max(cornerCoord, 0.0));\n"
            + "    } else {\n"
            + "        float gradX = step(cornerCoord.y, cornerCoord.x);\n"
            + "        return sign(coord) * float2(gradX, 1.0 - gradX);\n"
            + "    }\n"
            + "}\n";

    private static final String REFRACTION_AGSL =
            "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float radius;\n"
            + "uniform float refractionHeight;\n"
            + "uniform float refractionAmount;\n"
            + "uniform float depthEffect;\n"
            + "uniform float chromaticAberration;\n"
            + ROUNDED_RECT_SDF
            + "float circleMap(float x) {\n"
            + "    return 1.0 - sqrt(1.0 - x * x);\n"
            + "}\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 halfSize = size * 0.5;\n"
            + "    float2 centeredCoord = coord - halfSize;\n"
            + "    float sd = sdRoundedRect(centeredCoord, halfSize, radius);\n"
            + "    if (-sd >= refractionHeight) {\n"
            + "        return content.eval(coord);\n"
            + "    }\n"
            + "    sd = min(sd, 0.0);\n"
            + "    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;\n"
            + "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
            + "    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius)"
            + " + depthEffect * normalize(centeredCoord));\n"
            + "    float2 refractedCoord = coord + d * grad;\n"
            + "    float dispersionIntensity = chromaticAberration"
            + " * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));\n"
            + "    float2 dispersedCoord = d * grad * dispersionIntensity;\n"
            + "    half4 color = half4(0.0);\n"
            + "    half4 red = content.eval(refractedCoord + dispersedCoord);\n"
            + "    color.r += red.r / 3.5;\n"
            + "    color.a += red.a / 7.0;\n"
            + "    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));\n"
            + "    color.r += orange.r / 3.5;\n"
            + "    color.g += orange.g / 7.0;\n"
            + "    color.a += orange.a / 7.0;\n"
            + "    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));\n"
            + "    color.r += yellow.r / 3.5;\n"
            + "    color.g += yellow.g / 3.5;\n"
            + "    color.a += yellow.a / 7.0;\n"
            + "    half4 green = content.eval(refractedCoord);\n"
            + "    color.g += green.g / 3.5;\n"
            + "    color.a += green.a / 7.0;\n"
            + "    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));\n"
            + "    color.g += cyan.g / 3.5;\n"
            + "    color.b += cyan.b / 3.0;\n"
            + "    color.a += cyan.a / 7.0;\n"
            + "    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));\n"
            + "    color.b += blue.b / 3.0;\n"
            + "    color.a += blue.a / 7.0;\n"
            + "    half4 purple = content.eval(refractedCoord - dispersedCoord);\n"
            + "    color.r += purple.r / 7.0;\n"
            + "    color.b += purple.b / 3.0;\n"
            + "    color.a += purple.a / 7.0;\n"
            + "    return color;\n"
            + "}\n";

    private static final String HIGHLIGHT_AGSL =
            "uniform float2 size;\n"
            + "uniform float radius;\n"
            + "layout(color) uniform half4 color;\n"
            + "uniform float angle;\n"
            + "uniform float falloff;\n"
            + ROUNDED_RECT_SDF
            + "half4 main(float2 coord) {\n"
            + "    float2 halfSize = size * 0.5;\n"
            + "    float2 centeredCoord = coord - halfSize;\n"
            + "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
            + "    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);\n"
            + "    float2 normal = float2(cos(angle), sin(angle));\n"
            + "    float d = dot(grad, normal);\n"
            + "    float intensity = pow(abs(d), falloff);\n"
            + "    return color * intensity;\n"
            + "}\n";

    private static Bitmap sNoise;

    private final float mCorner;
    private final int mTint;
    /** Liquid: lens and specular rim (floating pills). Off: a flat frosted bar (top bar). */
    private final boolean mLiquid;
    private final float mDensity;
    private final Paint mTintPaint = new Paint();
    private final Paint mNoisePaint = new Paint();
    private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRimRect = new RectF();
    private final int[] mLoc = new int[2];
    private final int[] mSourceLoc = new int[2];
    private int mRecordedX = Integer.MIN_VALUE;
    private int mRecordedY = Integer.MIN_VALUE;
    private int mEffectW;
    private int mEffectH;

    @Nullable private GlassSource mSource;
    /** Sits on glass already (glassOnGlass): its tint is drawn as given, see-through. */
    private final boolean mOnGlass;
    @Nullable private RenderNode mNode;

    private final ViewTreeObserver.OnPreDrawListener mTrackPosition = () -> {
        if (mSource != null && hasMoved()) {
            invalidate(); // re-record at the new offset; content changes need no help
        }
        return true;
    };

    public GlassView(@NonNull Context context) {
        this(context, null);
    }

    public GlassView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.GlassView);
        mCorner = a.getDimension(R.styleable.GlassView_glassCornerRadius, 24 * mDensity);
        mTint = a.getColor(R.styleable.GlassView_glassTint, 0x66121212);
        mLiquid = a.getBoolean(R.styleable.GlassView_glassLiquid, true) && Glass.isLiquid(context);
        mOnGlass = a.getBoolean(R.styleable.GlassView_glassOnGlass, false);
        a.recycle();

        setWillNotDraw(false);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius(view.getWidth(), view.getHeight()));
            }
        });
        setClipToOutline(true);

        mNoisePaint.setShader(new BitmapShader(noise(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        mNoisePaint.setAlpha(NOISE_ALPHA);
        // Kyant's Highlight.Default: a 0.5dp line (stroked 1dp across the edge), softened by 0.25dp.
        mRimPaint.setStyle(Paint.Style.STROKE);
        mRimPaint.setStrokeWidth(mDensity);
        mRimPaint.setMaskFilter(new BlurMaskFilter(0.25f * mDensity, BlurMaskFilter.Blur.NORMAL));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mRimPaint.setBlendMode(BlendMode.PLUS);
        }
    }

    /** The page this glass frosts; it must not contain this view. */
    public void setSource(@Nullable GlassSource source) {
        mSource = source;
        if (source != null && source.node != null && mNode == null) {
            mNode = new RenderNode("VoidTube glass");
        }
        mRecordedX = Integer.MIN_VALUE;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(mTrackPosition);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(mTrackPosition);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mRimRect.set(0, 0, w, h);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mRimPaint.setShader(highlight(w, h));
        } else {
            // No runtime shaders: light from above, a faint bounce at the bottom.
            mRimPaint.setShader(new LinearGradient(0, 0, 0, h,
                    new int[] {0x66FFFFFF, 0x14FFFFFF, 0x0AFFFFFF, 0x2EFFFFFF},
                    new float[] {0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        boolean live = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && mNode != null && mSource != null
                && mSource.node != null && canvas.isHardwareAccelerated() && getWidth() > 0 && getHeight() > 0;
        if (live) {
            drawBackdrop(canvas);
            mTintPaint.setColor(mTint);
        } else if (mOnGlass) {
            mTintPaint.setColor(mTint);
        } else {
            mTintPaint.setColor((mTint & 0x00FFFFFF) | (FALLBACK_ALPHA << 24));
        }
        canvas.drawRect(0, 0, getWidth(), getHeight(), mTintPaint);
        if (mLiquid) {
            // VOIDXDEV(amoled): only the floating glass is grainy - the flat top bar stays true black.
            canvas.drawRect(0, 0, getWidth(), getHeight(), mNoisePaint);
            float r = radius(getWidth(), getHeight());
            canvas.drawRoundRect(mRimRect, r, r, mRimPaint);
        }
        super.onDraw(canvas);
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private void drawBackdrop(Canvas canvas) {
        RenderNode node = mNode;
        hasMoved();
        mRecordedX = mLoc[0] - mSourceLoc[0];
        mRecordedY = mLoc[1] - mSourceLoc[1];
        int w = getWidth();
        int h = getHeight();
        node.setPosition(0, 0, w, h);
        if (w != mEffectW || h != mEffectH) {
            mEffectW = w;
            mEffectH = h;
            node.setRenderEffect(effect(w, h));
        }
        Canvas recording = node.beginRecording();
        recording.translate(-mRecordedX, -mRecordedY);
        // The source's own (opaque) background first: its node holds only the children, and text
        // on a bare background would blur to near-transparent and let the sharp page show through.
        android.graphics.drawable.Drawable ground = mSource.getBackground();
        if (ground != null) {
            ground.draw(recording);
        }
        recording.drawRenderNode(mSource.node);
        node.endRecording();
        canvas.drawRenderNode(node);
    }

    /** Saturate, blur, then (API 33+) refract the blurred backdrop - BitChord's order. */
    @RequiresApi(api = Build.VERSION_CODES.S)
    private RenderEffect effect(int w, int h) {
        float blur = BLUR_DP * mDensity;
        ColorMatrix saturate = new ColorMatrix();
        saturate.setSaturation(SATURATION);
        RenderEffect frost = RenderEffect.createBlurEffect(blur, blur,
                RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(saturate)), Shader.TileMode.CLAMP);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !mLiquid) {
            return frost;
        }
        RuntimeShader lens = new RuntimeShader(REFRACTION_AGSL);
        lens.setFloatUniform("size", w, h);
        lens.setFloatUniform("radius", radius(w, h));
        lens.setFloatUniform("refractionHeight", REFRACTION_HEIGHT_DP * mDensity);
        lens.setFloatUniform("refractionAmount", -REFRACTION_AMOUNT_DP * mDensity);
        lens.setFloatUniform("depthEffect", 1f);
        lens.setFloatUniform("chromaticAberration", 1f);
        return RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(lens, "content"), frost);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private Shader highlight(int w, int h) {
        RuntimeShader shader = new RuntimeShader(HIGHLIGHT_AGSL);
        shader.setFloatUniform("size", w, h);
        shader.setFloatUniform("radius", radius(w, h));
        shader.setColorUniform("color", 0x80FFFFFF);
        shader.setFloatUniform("angle", (float) Math.toRadians(HIGHLIGHT_ANGLE));
        shader.setFloatUniform("falloff", 1f);
        return shader;
    }

    private float radius(int w, int h) {
        return Math.min(mCorner, Math.min(w, h) / 2f);
    }

    /** Refreshes the on-screen offsets; true when they differ from the last recording. */
    private boolean hasMoved() {
        if (mSource == null) {
            return false;
        }
        getLocationInWindow(mLoc);
        mSource.getLocationInWindow(mSourceLoc);
        return mLoc[0] - mSourceLoc[0] != mRecordedX || mLoc[1] - mSourceLoc[1] != mRecordedY;
    }

    static Bitmap noise() {
        if (sNoise == null) {
            int size = 96;
            int[] px = new int[size * size];
            Random random = new Random(7);
            for (int i = 0; i < px.length; i++) {
                int v = random.nextInt(256);
                px[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
            sNoise = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
        }
        return sNoise;
    }
}
