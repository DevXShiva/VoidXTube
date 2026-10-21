package com.newtube.mobile.ui.common;

import android.os.Build;
import android.view.View;
import android.view.ViewTreeObserver;

/**
 * NEWTUBE(motion): "run this once the window has put its current content on screen" for hand-offs
 * between two windows (the translucent player over a host screen). A posted runnable or an animation
 * callback only proves time passed, not that a frame with the change was submitted. From Android 10
 * a frame-commit callback says exactly that; before it, the next draw is the closest signal. Either
 * way the action also runs after {@code fallbackMs}, so a window that never draws (an OEM that
 * skips paused windows, a screen already covered) cannot strand the hand-off.
 */
public final class FrameGate {
    private FrameGate() {
    }

    public static void afterNextFrame(View view, long fallbackMs, Runnable action) {
        Once once = new Once(view, action);
        view.postDelayed(once, fallbackMs);
        ViewTreeObserver observer = view.getViewTreeObserver();
        if (!observer.isAlive()) {
            return; // the fallback runs it
        }
        if (Build.VERSION.SDK_INT >= 29) {
            observer.registerFrameCommitCallback(() -> view.post(once));
        } else {
            observer.addOnDrawListener(new ViewTreeObserver.OnDrawListener() {
                @Override
                public void onDraw() {
                    // A draw listener may not remove itself while draws are dispatched.
                    view.post(() -> {
                        ViewTreeObserver current = view.getViewTreeObserver();
                        if (current.isAlive()) {
                            current.removeOnDrawListener(this);
                        }
                        view.post(once);
                    });
                }
            });
        }
        view.invalidate();
    }

    private static final class Once implements Runnable {
        private final View mView;
        private final Runnable mAction;
        private boolean mRan;

        Once(View view, Runnable action) {
            mView = view;
            mAction = action;
        }

        @Override
        public void run() {
            if (mRan) {
                return;
            }
            mRan = true;
            mView.removeCallbacks(this);
            mAction.run();
        }
    }
}
