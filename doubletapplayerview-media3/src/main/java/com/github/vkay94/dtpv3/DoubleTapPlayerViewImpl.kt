package com.github.vkay94.dtpv3

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView

/**
 * Double-tap player surface for the media3 touch player.
 *
 * Unlike the original module's class this does NOT extend media3's PlayerView: the vendored
 * exoplayer2-ui and media3-ui libraries both ship a `layout/exo_player_view` resource, and the
 * app's resource merger can only keep one - inflating media3 PlayerView then explodes on the
 * other library's inner views (ClassCastException on AspectRatioFrameLayout). The touch player
 * only ever used PlayerView with `surface_type="none"` + `use_controller="false"` anyway, so all
 * that's actually needed is rebuilt here directly: an [AspectRatioFrameLayout] content box
 * (the Activity parents its session TextureView into it), a [SubtitleView], video-size driven
 * aspect ratio, and the double-tap gesture detection.
 */
open class DoubleTapPlayerViewImpl @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), DoubleTapPlayerView {

    private val gestureDetector: GestureDetector
    private val gestureListener: DoubleTapGestureListener = DoubleTapGestureListener(this)

    /**
     * NEWTUBE(motion): a single tap acts when the finger lifts, not once the double-tap timeout
     * has ruled out a second tap (~300 ms of "the tap did nothing"). A second tap then turns the
     * pair into a double tap after all: [doubleTapBeganListener] is told first, so the owner can
     * undo what the first tap did.
     */
    var isInstantSingleTap = false

    fun interface DoubleTapBeganListener {
        fun onDoubleTapBegan(posX: Float)
    }

    var doubleTapBeganListener: DoubleTapBeganListener? = null

    /**
     * NEWTUBE(hold-speed): press-and-hold on the video (YouTube's 2x). [onHoldStart] is asked when
     * a still finger passes the long-press timeout - not during a double-tap seek - and answers
     * whether it took the hold; [onHoldEnd] follows when that finger lifts or the touch is taken.
     */
    interface HoldListener {
        fun onHoldStart(x: Float, y: Float): Boolean
        fun onHoldEnd()
    }

    var holdListener: HoldListener? = null

    private var isHolding = false

    /** The finger that holds: its lift ends the hold even while another finger stays down. */
    private var holdPointerId = MotionEvent.INVALID_POINTER_ID

    private val contentFrame: AspectRatioFrameLayout = AspectRatioFrameLayout(context)
    private val subtitleView: SubtitleView = SubtitleView(context)

    private var player: Player? = null

    private val componentListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            updateAspectRatio(videoSize)
        }
    }

    private var controller: PlayerDoubleTapListener? = null
        get() = gestureListener.controls
        set(value) {
            gestureListener.controls = value
            field = value
        }

    init {
        gestureDetector = GestureDetector(context, gestureListener)

        contentFrame.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        addView(contentFrame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            .apply { gravity = Gravity.CENTER })

        // Subtitles live inside the content frame so they track the video box, not the letterbox.
        subtitleView.setUserDefaultStyle()
        subtitleView.setUserDefaultTextSize()
        contentFrame.addView(subtitleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    // ------------------------------------------------------------------------------
    // The PlayerView-like surface the Activity uses
    // ------------------------------------------------------------------------------

    /** The aspect-ratio box the Activity parents its code-managed TextureView into. */
    fun getContentFrame(): FrameLayout = contentFrame

    fun getSubtitleView(): SubtitleView = subtitleView

    fun setPlayer(newPlayer: Player?) {
        if (player === newPlayer) {
            return
        }
        player?.removeListener(componentListener)
        player = newPlayer
        if (newPlayer != null) {
            newPlayer.addListener(componentListener)
            updateAspectRatio(newPlayer.videoSize)
        }
    }

    fun setResizeMode(mode: Int) {
        contentFrame.resizeMode = mode
    }

    fun getResizeMode(): Int = contentFrame.resizeMode

    @Suppress("UNUSED_PARAMETER")
    fun setShutterBackgroundColor(color: Int) {
        // No shutter view here: the Activity's own loading-still covers stale frames.
    }

    private fun updateAspectRatio(videoSize: VideoSize) {
        if (videoSize.width == 0 || videoSize.height == 0) {
            return
        }
        contentFrame.setAspectRatio(
            videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
    }

    // ------------------------------------------------------------------------------
    // Double-tap handling (unchanged from the original module)
    // ------------------------------------------------------------------------------

    override val playerWidth: Int
        get() = width

    override var isDoubleTapEnabled = true

    override var doubleTapDelay: Long = 700
        get() = gestureListener.doubleTapDelay
        set(value) {
            gestureListener.doubleTapDelay = value
            field = value
        }

    override fun controller(controller: PlayerDoubleTapListener?) = apply { this.controller = controller }

    override fun isInDoubleTapMode(): Boolean = gestureListener.isDoubleTapping

    override fun keepInDoubleTapMode() {
        gestureListener.keepInDoubleTapMode()
    }

    override fun cancelInDoubleTapMode() {
        gestureListener.cancelInDoubleTapMode()
    }

    /** Called by the gesture listener: a still press passed the long-press timeout. */
    private fun onLongPressed(e: MotionEvent) {
        val listener = holdListener ?: return
        if (gestureListener.isDoubleTapping || isHolding) {
            return
        }
        if (listener.onHoldStart(e.x, e.y)) {
            isHolding = true
            holdPointerId = e.getPointerId(0)
            // The hold owns this finger now: no swipe-to-minimize, no pinch taking it over.
            parent?.requestDisallowInterceptTouchEvent(true)
        }
    }

    private fun endHold() {
        if (isHolding) {
            isHolding = false
            holdPointerId = MotionEvent.INVALID_POINTER_ID
            holdListener?.onHoldEnd()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (isHolding && (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL
                    || (ev.actionMasked == MotionEvent.ACTION_POINTER_UP
                        && ev.getPointerId(ev.actionIndex) == holdPointerId))) {
            gestureDetector.onTouchEvent(ev)
            endHold()
            return true
        }
        if (isDoubleTapEnabled) {
            gestureDetector.onTouchEvent(ev)
            // NEWTUBE(motion): a parent took the touch (drag, pinch) - the detector forgets its
            // taps, and the double-tap mode must not outlive them to swallow the next tap.
            if (ev.actionMasked == MotionEvent.ACTION_CANCEL && gestureListener.isDoubleTapping) {
                gestureListener.cancelInDoubleTapMode()
            }

            // Do not trigger original behavior when double tapping
            // otherwise the controller would show/hide - it would flack
            return true
        }
        return super.onTouchEvent(ev)
    }

    /**
     * Gesture Listener for double tapping
     */
    private class DoubleTapGestureListener(private val rootView: DoubleTapPlayerViewImpl) : GestureDetector.SimpleOnGestureListener() {

        private val mHandler = Handler(Looper.getMainLooper())
        private val mRunnable = Runnable {
            if (DEBUG) Log.d(TAG, "Runnable called")
            isDoubleTapping = false
            controls?.onDoubleTapFinished()
        }

        var controls: PlayerDoubleTapListener? = null
        var isDoubleTapping = false
        var doubleTapDelay: Long = 650

        fun keepInDoubleTapMode() {
            isDoubleTapping = true
            mHandler.removeCallbacks(mRunnable)
            mHandler.postDelayed(mRunnable, doubleTapDelay)
        }

        fun cancelInDoubleTapMode() {
            mHandler.removeCallbacks(mRunnable)
            isDoubleTapping = false
            controls?.onDoubleTapFinished()
        }

        override fun onLongPress(e: MotionEvent) {
            rootView.onLongPressed(e)
        }

        override fun onDown(e: MotionEvent): Boolean {
            if (isDoubleTapping) {
                controls?.onDoubleTapProgressDown(e.x, e.y)
                return true
            }
            return super.onDown(e)
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (isDoubleTapping) {
                if (DEBUG) Log.d(TAG, "onSingleTapUp: isDoubleTapping = true")
                controls?.onDoubleTapProgressUp(e.x, e.y)
                return true
            }
            if (rootView.isInstantSingleTap) {
                return rootView.performClick()
            }
            return super.onSingleTapUp(e)
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (isDoubleTapping) return true
            if (rootView.isInstantSingleTap) return true // already handled on the tap's release
            if (DEBUG) Log.d(TAG, "onSingleTapConfirmed: isDoubleTap = false")
            return rootView.performClick()
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (DEBUG) Log.d(TAG, "onDoubleTap")
            if (!isDoubleTapping) {
                if (rootView.isInstantSingleTap) {
                    rootView.doubleTapBeganListener?.onDoubleTapBegan(e.x)
                }
                isDoubleTapping = true
                keepInDoubleTapMode()
                controls?.onDoubleTapStarted(e.x, e.y)
            }
            return true
        }

        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP && isDoubleTapping) {
                if (DEBUG) Log.d(TAG, "onDoubleTapEvent, ACTION_UP")
                controls?.onDoubleTapProgressUp(e.x, e.y)
                return true
            }
            return super.onDoubleTapEvent(e)
        }

        companion object {
            private const val TAG = ".DTGListener"
            private var DEBUG = false
        }
    }
}
