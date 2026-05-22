package com.pdfviewer.sdk.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.pdfviewer.sdk.renderer.PdfRenderer

/**
 * Transparent overlay that draws flat highlight rectangles on top of a PDF page.
 * - Yellow: all search matches
 * - Orange: current active match
 * No borders — just clean flat color like a real highlighter.
 */
class HighlightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val highlightPaint = Paint().apply {
        color = Color.parseColor("#66FFEB3B") // semi-transparent yellow
        style = Paint.Style.FILL
    }

    private val currentMatchPaint = Paint().apply {
        color = Color.parseColor("#66FF5722") // semi-transparent orange
        style = Paint.Style.FILL
    }

    var highlights: List<PdfRenderer.HighlightRect> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var currentMatchHighlights: List<PdfRenderer.HighlightRect> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        for (rect in highlights) {
            canvas.drawRect(rect.left * w, rect.top * h, rect.right * w, rect.bottom * h, highlightPaint)
        }

        for (rect in currentMatchHighlights) {
            canvas.drawRect(rect.left * w, rect.top * h, rect.right * w, rect.bottom * h, currentMatchPaint)
        }
    }
}
