package com.pdfviewer.sdk.gestures

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.pdfviewer.sdk.core.PdfConfig
import com.pdfviewer.sdk.utils.clamp

/**
 * Unified gesture handler for the PDF viewer.
 *
 * Combines a [ScaleGestureDetector] for pinch-to-zoom and a [GestureDetector] for double-tap zoom.
 * The current scale factor is exposed via [scaleFactor] and changes are broadcast through [onScaleChanged].
 */
class PdfGestureHandler(
    context: Context,
    private val config: PdfConfig,
    /** Invoked on every scale change with the new scale value. */
    private val onScaleChanged: (Float) -> Unit
) {
    /** Current zoom level. 1.0 = fit-width. */
    var scaleFactor: Float = 1.0f
        private set

    /** Whether double-tap toggled us into the zoomed state. */
    private var isDoubleTapZoomed = false

    // ── Scale (pinch) detector ───────────────────────────────────────────────

    private val scaleListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor = (scaleFactor * detector.scaleFactor).clamp(config.minZoom, config.maxZoom)
            onScaleChanged(scaleFactor)
            return true
        }
    }

    val scaleGestureDetector: ScaleGestureDetector =
        ScaleGestureDetector(context, scaleListener).apply {
            // Reduce the span-slop so zoom kicks in faster
            isQuickScaleEnabled = false
        }

    // ── Tap (double-tap) detector ────────────────────────────────────────────

    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            scaleFactor = if (isDoubleTapZoomed) {
                config.minZoom
            } else {
                config.doubleTapZoom
            }
            isDoubleTapZoomed = !isDoubleTapZoomed
            onScaleChanged(scaleFactor)
            return true
        }
    }

    val gestureDetector: GestureDetector = GestureDetector(context, gestureListener)

    // ── Public helpers ───────────────────────────────────────────────────────

    /** Dispatch a touch event to both detectors. Returns true if either consumed it. */
    fun onTouchEvent(event: MotionEvent): Boolean {
        var handled = scaleGestureDetector.onTouchEvent(event)
        handled = gestureDetector.onTouchEvent(event) || handled
        return handled
    }

    /** Programmatic zoom-in by a fixed step (1.25×). */
    fun zoomIn() {
        scaleFactor = (scaleFactor * 1.25f).clamp(config.minZoom, config.maxZoom)
        isDoubleTapZoomed = scaleFactor > config.minZoom
        onScaleChanged(scaleFactor)
    }

    /** Programmatic zoom-out by a fixed step (0.8×). */
    fun zoomOut() {
        scaleFactor = (scaleFactor * 0.8f).clamp(config.minZoom, config.maxZoom)
        isDoubleTapZoomed = scaleFactor > config.minZoom
        onScaleChanged(scaleFactor)
    }

    /** Reset zoom to min (fit-width). */
    fun resetZoom() {
        scaleFactor = config.minZoom
        isDoubleTapZoomed = false
        onScaleChanged(scaleFactor)
    }
}
