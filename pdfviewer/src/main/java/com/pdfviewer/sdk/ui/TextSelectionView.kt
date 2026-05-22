package com.pdfviewer.sdk.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.pdfviewer.sdk.renderer.PdfRenderer
import kotlinx.coroutines.*

/**
 * Transparent overlay on each PDF page that handles text selection via double-tap.
 *
 * Double-tap on a word → select it → highlight blue → auto-copy to clipboard + fill search.
 * Tap anywhere → clear selection.
 */
class TextSelectionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val selectionPaint = Paint().apply {
        color = Color.parseColor("#4D2196F3")
        style = Paint.Style.FILL
    }

    var selectionRects: List<PdfRenderer.HighlightRect> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var renderer: PdfRenderer? = null
    var pageIndex: Int = -1
    var renderWidth: Int = 0
    var renderHeight: Int = 0

    private var selectionJob: Job? = null

    private fun getScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Called when text is selected — provides (pageIndex, startChar, endChar). */
    var onTextSelected: ((pageIndex: Int, startChar: Int, endChar: Int) -> Unit)? = null

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                selectWordAt(e.x, e.y)
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (selectionRects.isNotEmpty()) {
                    clearSelection()
                    return true
                }
                return false
            }
        }
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        // Always return true on DOWN to receive the full touch sequence for double-tap
        return event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP
    }

    private fun selectWordAt(x: Float, y: Float) {
        val r = renderer ?: return
        if (pageIndex < 0 || renderWidth <= 0 || renderHeight <= 0) return

        selectionJob?.cancel()
        selectionJob = getScope().launch {
            val deviceX = ((x / width) * renderWidth).toInt()
            val deviceY = ((y / height) * renderHeight).toInt()

            val charIdx = r.getCharIndexAtPos(pageIndex, renderWidth, renderHeight, deviceX, deviceY)
            if (charIdx < 0) return@launch

            val (wordStart, wordEnd) = r.getWordBounds(pageIndex, charIdx)
            if (wordStart < 0 || wordEnd < wordStart) return@launch

            // Show highlight
            val rects = r.getSelectionRects(pageIndex, wordStart, wordEnd, renderWidth, renderHeight)
            withContext(Dispatchers.Main) {
                selectionRects = rects
            }

            // Notify — PDFView will copy + fill search
            onTextSelected?.invoke(pageIndex, wordStart, wordEnd)
        }
    }

    fun clearSelection() {
        selectionRects = emptyList()
        selectionJob?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (selectionRects.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()

        for (rect in selectionRects) {
            canvas.drawRect(rect.left * w, rect.top * h, rect.right * w, rect.bottom * h, selectionPaint)
        }
    }
}
