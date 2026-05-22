package com.pdfviewer.sdk.gestures

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Zoom + pan wrapper for RecyclerView.
 *
 * Touch priority:
 * 1. Two fingers → pinch zoom (consumed, child gets nothing)
 * 2. Zoomed + drag → pan (horizontal always, vertical if single page)
 * 3. Everything else → passed to child (RecyclerView vertical scroll)
 */
class ZoomableLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var minScale: Float = 0.5f
    var maxScale: Float = 10.0f
    var doubleTapScale: Float = 2.5f
    var onScaleChanged: ((Float) -> Unit)? = null
    var onScaleSettled: ((Float) -> Unit)? = null

    private val zoomMatrix = Matrix()
    private val matrixValues = FloatArray(9)

    // Cached values — updated once per frame instead of 3x per access
    private var cachedScale = 1f
    private var cachedTx = 0f
    private var cachedTy = 0f

    val currentScale: Float get() = cachedScale

    private fun syncFromMatrix() {
        zoomMatrix.getValues(matrixValues)
        cachedScale = matrixValues[Matrix.MSCALE_X]
        cachedTx = matrixValues[Matrix.MTRANS_X]
        cachedTy = matrixValues[Matrix.MTRANS_Y]
    }

    private var isDoubleTapZoomed = false
    private val lastTouch = PointF()
    private var isScaling = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var cachedCanScroll: Boolean? = null

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (cachedScale * detector.scaleFactor).coerceIn(minScale, maxScale)
                val factor = newScale / cachedScale
                zoomMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                syncFromMatrix()
                applyTransform()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
                onScaleSettled?.invoke(cachedScale)
            }
        }
    )

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)

        if (scaleDetector.isInProgress || isScaling) {
            return true
        }

        val isZoomed = cachedScale < 0.95f || cachedScale > 1.05f

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = ev.getPointerId(0)
                lastTouch.set(ev.x, ev.y)
                // Cache scroll ability once per gesture, not per MOVE
                cachedCanScroll = null
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isZoomed && activePointerId != MotionEvent.INVALID_POINTER_ID) {
                    val idx = ev.findPointerIndex(activePointerId)
                    if (idx != -1) {
                        val dx = ev.getX(idx) - lastTouch.x
                        val dy = ev.getY(idx) - lastTouch.y

                        // Check scroll ability once per gesture
                        if (cachedCanScroll == null) {
                            val child = getChildAt(0)
                            cachedCanScroll = child?.canScrollVertically(-1) == true ||
                                    child?.canScrollVertically(1) == true
                        }

                        if (cachedCanScroll == true) {
                            if (abs(dx) > 3) {
                                zoomMatrix.postTranslate(dx, 0f)
                                syncFromMatrix()
                                applyTransform()
                            }
                            lastTouch.set(ev.getX(idx), ev.getY(idx))
                        } else {
                            if (abs(dx) > 2 || abs(dy) > 2) {
                                zoomMatrix.postTranslate(dx, dy)
                                syncFromMatrix()
                                applyTransform()
                            }
                            lastTouch.set(ev.getX(idx), ev.getY(idx))
                            return true
                        }
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val leavingId = ev.getPointerId(ev.actionIndex)
                if (leavingId == activePointerId) {
                    val newIdx = if (ev.actionIndex == 0) 1 else 0
                    if (newIdx < ev.pointerCount) {
                        activePointerId = ev.getPointerId(newIdx)
                        lastTouch.set(ev.getX(newIdx), ev.getY(newIdx))
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                cachedCanScroll = null
            }
        }

        return super.dispatchTouchEvent(ev)
    }

    private var lastReportedScale = 1.0f

    private fun applyTransform() {
        val child = getChildAt(0) ?: return
        child.pivotX = 0f
        child.pivotY = 0f
        child.scaleX = cachedScale
        child.scaleY = cachedScale
        child.translationX = cachedTx
        child.translationY = cachedTy
        if (cachedScale != lastReportedScale) {
            lastReportedScale = cachedScale
            onScaleChanged?.invoke(cachedScale)
        }
    }

    private fun animateZoomTo(targetScale: Float, focusX: Float, focusY: Float) {
        val startScale = cachedScale
        val startTx = cachedTx
        val startTy = cachedTy
        val ratio = targetScale / startScale
        val targetTx = focusX - ratio * (focusX - startTx)
        val targetTy = focusY - ratio * (focusY - startTy)
        val finalTx = if (targetScale == 1.0f) 0f else targetTx
        val finalTy = if (targetScale == 1.0f) 0f else targetTy

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedFraction
                val s = startScale + (targetScale - startScale) * t
                val tx = startTx + (finalTx - startTx) * t
                val ty = startTy + (finalTy - startTy) * t
                zoomMatrix.setScale(s, s)
                zoomMatrix.postTranslate(tx, ty)
                syncFromMatrix()
                applyTransform()
                if (t >= 1.0f) onScaleSettled?.invoke(cachedScale)
            }
            start()
        }
    }

    fun zoomIn() {
        val target = (cachedScale * 1.25f).coerceIn(minScale, maxScale)
        animateZoomTo(target, width / 2f, height / 2f)
    }

    fun zoomOut() {
        val target = (cachedScale * 0.8f).coerceIn(minScale, maxScale)
        animateZoomTo(target, width / 2f, height / 2f)
    }

    fun resetZoom() {
        isDoubleTapZoomed = false
        animateZoomTo(1.0f, width / 2f, height / 2f)
    }
}
