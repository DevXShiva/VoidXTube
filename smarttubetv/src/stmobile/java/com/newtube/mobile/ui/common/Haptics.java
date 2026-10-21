package com.newtube.mobile.ui.common;

import android.app.Dialog;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.PopupWindow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

/**
 * NEWTUBE(haptics): the app's haptic vocabulary, taken from what the YouTube app plays (read from
 * the vibrator service's history while using YouTube 21.18): a light tick when the seek bar crosses
 * a chapter boundary, a click when it snaps back to where the drag started ("Release to cancel")
 * and on a like, and a firm buzz when press-and-hold turns on 2x speed. Taps, double-tap seeks,
 * swipes and play/pause stay silent there.
 *
 * <p>VOIDXDEV(haptics): VoidTube answers every tap too - a button, a card, a chip, a switch, a menu
 * row: {@link #onTouch} gives one click to whatever clickable view a tap lands on, for every
 * screen (MobileActivity) and every sheet, dialog and popup ({@link #watch}). The player's own
 * surface opts out (hapticFeedbackEnabled=false) - its taps are the double-tap seek's, which
 * click per seek instead.</p>
 *
 * <p>Drags that commit on release (swipe the player down, swipe the mini card away, pull to
 * refresh) speak the Pixel's own drag language instead, read the same way from a Pixel 9 on
 * Android 17 while its owner swiped notifications away and went home: a faint grain of LOW_TICKs
 * while something is held back ({@link #tension}), and one CLICK at 0.7 the moment it lets go or
 * sticks again ({@link #threshold}). Those are vibration primitives, which only the Vibrator plays;
 * a phone without them gets the platform's threshold constant and no grain.</p>
 *
 * <p>performHapticFeedback needs no permission and follows the system's touch-feedback setting,
 * so a phone with vibration off stays silent; the primitives check the same setting themselves and
 * play as touch feedback, which the system scales or mutes with the touch intensity.</p>
 */
public final class Haptics {

    /**
     * The grain: five LOW_TICKs, at most one burst per 60 ms, scaled like SystemUI's notification
     * pull (MagneticNotificationRowManagerImpl: 0.2 * n^1.27, then ^(1/0.89) for perception, where
     * n reaches 0.5 at the detach point). It is barely there - the Pixel's own peaks at ~0.06.
     */
    private static final int TENSION_TICKS = 5;
    private static final long TENSION_INTERVAL_MS = 60;
    private static final float TENSION_GAIN = 0.2f;
    /** SystemUI's SWIPE_THRESHOLD_INDICATOR token: one CLICK primitive at 0.7. */
    private static final float THRESHOLD_CLICK_SCALE = 0.7f;
    /**
     * No grain this soon after a click: a new vibration cancels the one playing, and a grain
     * started with the click (the same touch event that crossed the line back) cut it after 1-5 ms
     * - the owner felt the click come back only sometimes (Pixel vibrator log: cancelled_superseded).
     */
    private static final long CLICK_GUARD_MS = 100;

    /** A click right after a tap's is the same press (a like clicks itself as it lands). */
    private static final long TAP_GUARD_MS = 80;

    private static long sLastTensionAt;
    private static long sLastTapAt;
    @Nullable
    private static View sTapTarget;
    private static float sDownX;
    private static float sDownY;
    private static final int[] sLocation = new int[2];
    private static long sLastThresholdAt;
    private static boolean sProbed;
    @Nullable
    private static Vibrator sComposer;

    private Haptics() {
    }

    /** A boundary crossed while dragging (a chapter on the seek bar, a zoom snap). */
    public static void tick(@Nullable View view) {
        if (view != null) {
            // SEGMENT_TICK is the platform's name for exactly this (API 34); before it,
            // CONTEXT_CLICK is the constant that plays the same light TICK effect.
            view.performHapticFeedback(Build.VERSION.SDK_INT >= 34
                    ? HapticFeedbackConstants.SEGMENT_TICK : HapticFeedbackConstants.CONTEXT_CLICK);
        }
    }

    /** A snap into a resting place, or an action taking effect (like, dislike). */
    public static void click(@Nullable View view) {
        if (view != null && SystemClock.uptimeMillis() - sLastTapAt > TAP_GUARD_MS) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
    }

    /** Press-and-hold switching a mode on. */
    public static void longPress(@Nullable View view) {
        if (view != null) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
    }

    /**
     * A drag crossed the point where letting go acts ({@code engaged}), or came back behind it.
     * The Pixel plays the same click both ways; the platform fallback has a pair.
     */
    public static void threshold(@Nullable View view, boolean engaged) {
        if (view == null) {
            return;
        }
        sLastThresholdAt = SystemClock.uptimeMillis();
        Vibrator composer = composer(view);
        if (composer != null) {
            vibrate(composer, VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, THRESHOLD_CLICK_SCALE)
                    .compose());
        } else if (Build.VERSION.SDK_INT >= 34) {
            view.performHapticFeedback(engaged ? HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                    : HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE);
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
    }

    /**
     * Something is being pulled against a hold; {@code progress} is how far toward its threshold
     * (0..1). Call it on every move - it paces itself - and it stays silent where the phone has no
     * primitives: a platform tick every 60 ms would be a buzz, not a grain.
     */
    public static void tension(@Nullable View view, float progress) {
        if (view == null || progress <= 0f) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - sLastTensionAt < TENSION_INTERVAL_MS || now - sLastThresholdAt < CLICK_GUARD_MS) {
            return;
        }
        Vibrator composer = composer(view);
        if (composer == null) {
            return;
        }
        sLastTensionAt = now;
        double pulled = 0.5 * Math.min(1f, progress);
        float scale = (float) Math.min(1.0, Math.pow(TENSION_GAIN * Math.pow(pulled, 1.27), 1 / 0.89));
        VibrationEffect.Composition grain = VibrationEffect.startComposition();
        for (int i = 0; i < TENSION_TICKS; i++) {
            grain.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, scale);
        }
        vibrate(composer, grain.compose());
    }

    /**
     * VOIDXDEV(haptics): feed a window's touches here; a tap (no drag, no long press) on an enabled
     * clickable view clicks. The view is found the way the touch is dispatched - topmost visible
     * child first, the deepest clickable one under the finger.
     */
    public static void onTouch(@Nullable View root, @NonNull MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                sDownX = event.getX();
                sDownY = event.getY();
                sTapTarget = root != null ? touchTarget(root, (int) sDownX, (int) sDownY) : null;
                break;
            case MotionEvent.ACTION_MOVE:
                if (sTapTarget != null && Math.hypot(event.getX() - sDownX, event.getY() - sDownY)
                        > ViewConfiguration.get(sTapTarget.getContext()).getScaledTouchSlop()) {
                    sTapTarget = null; // a scroll or a drag, not a tap
                }
                break;
            case MotionEvent.ACTION_UP:
                View target = sTapTarget;
                sTapTarget = null;
                boolean held = event.getEventTime() - event.getDownTime() >= ViewConfiguration.getLongPressTimeout();
                if (target != null && target.isEnabled() && target.isClickable() && target.isHapticFeedbackEnabled()
                        && !(target instanceof EditText) && !(held && target.isLongClickable())) {
                    sLastTapAt = SystemClock.uptimeMillis();
                    target.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                sTapTarget = null;
                break;
            default:
                break;
        }
    }

    /** VOIDXDEV(haptics): a dialog's or sheet's taps click like the screens' do. Returns the dialog. */
    @NonNull
    public static <T extends Dialog> T watch(@NonNull T dialog) {
        Window window = dialog.getWindow();
        if (window == null) {
            return dialog;
        }
        Window.Callback callback = window.getCallback();
        window.setCallback((Window.Callback) Proxy.newProxyInstance(Window.Callback.class.getClassLoader(),
                new Class<?>[]{Window.Callback.class}, (proxy, method, args) -> {
                    if ("dispatchTouchEvent".equals(method.getName())) {
                        onTouch(window.peekDecorView(), (MotionEvent) args[0]);
                    } else if ("onAttachedToWindow".equals(method.getName())
                            && dialog instanceof com.google.android.material.bottomsheet.BottomSheetDialog) {
                        SheetGlass.frost(dialog); // VOIDXDEV(glass): every sheet passes through here
                    }
                    try {
                        return method.invoke(callback, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }));
        return dialog;
    }

    /** VOIDXDEV(haptics): a popup menu's taps click too. */
    public static void watch(@NonNull PopupWindow popup) {
        popup.setTouchInterceptor((v, event) -> {
            onTouch(v, event);
            return false;
        });
    }

    @Nullable
    private static View touchTarget(View view, int x, int y) {
        if (view.getVisibility() != View.VISIBLE) {
            return null;
        }
        view.getLocationInWindow(sLocation);
        if (x < sLocation[0] || y < sLocation[1]
                || x >= sLocation[0] + view.getWidth() || y >= sLocation[1] + view.getHeight()) {
            return null;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View hit = touchTarget(group.getChildAt(i), x, y);
                if (hit != null) {
                    return hit;
                }
            }
        }
        // A disabled clickable view still takes the touch (and stays silent).
        return view.isClickable() || view.isLongClickable() ? view : null;
    }

    /** The vibrator when it can compose primitives and touch feedback is on, else null. */
    @Nullable
    private static Vibrator composer(View view) {
        if (Build.VERSION.SDK_INT < 33 || !view.isHapticFeedbackEnabled()) {
            return null;
        }
        Context context = view.getContext();
        if (Settings.System.getInt(context.getContentResolver(),
                Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) {
            return null;
        }
        if (!sProbed) {
            sProbed = true;
            VibratorManager manager = context.getApplicationContext().getSystemService(VibratorManager.class);
            Vibrator vibrator = manager != null ? manager.getDefaultVibrator() : null;
            if (vibrator != null && vibrator.hasVibrator() && vibrator.areAllPrimitivesSupported(
                    VibrationEffect.Composition.PRIMITIVE_CLICK,
                    VibrationEffect.Composition.PRIMITIVE_LOW_TICK)) {
                sComposer = vibrator;
            }
        }
        return sComposer;
    }

    private static void vibrate(Vibrator vibrator, VibrationEffect effect) {
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH));
        }
    }
}
