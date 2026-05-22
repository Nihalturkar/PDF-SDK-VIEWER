package com.pdfviewer.sdk.core

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import io.legere.pdfiumandroid.PdfDocument as LegerePdfDocument
import io.legere.pdfiumandroid.PdfPage
import io.legere.pdfiumandroid.PdfTextPage
import io.legere.pdfiumandroid.PdfiumCore
import java.io.File

/**
 * Thin wrapper around io.legere PdfiumCore that manages a single open PDF document.
 *
 * Exposes rendering, text extraction, text search, and coordinate mapping via native PDFium APIs.
 * All public methods are **not thread-safe** — callers must synchronise externally.
 */
class PdfDocument(
    private val pdfiumCore: PdfiumCore
) {
    private var document: LegerePdfDocument? = null
    private var fileDescriptor: ParcelFileDescriptor? = null

    /** Opened pages cache — keeps pages open for text operations. */
    private val openPages = mutableMapOf<Int, PdfPage>()
    private val openTextPages = mutableMapOf<Int, PdfTextPage>()

    val pageCount: Int
        get() = document?.getPageCount() ?: 0

    fun open(file: File) {
        close()
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        fileDescriptor = fd
        document = pdfiumCore.newDocument(fd)
    }

    fun open(data: ByteArray) {
        close()
        document = pdfiumCore.newDocument(data)
    }

    /**
     * Returns the intrinsic size of [pageIndex] in PDF points (1/72 inch).
     */
    fun getPageSize(pageIndex: Int): Pair<Int, Int> {
        val page = getPage(pageIndex) ?: return 0 to 0
        val w = page.getPageWidthPoint()
        val h = page.getPageHeightPoint()
        return w to h
    }

    /**
     * Renders [pageIndex] into a new ARGB_8888 Bitmap of the given pixel dimensions.
     */
    fun renderPage(pageIndex: Int, width: Int, height: Int): Bitmap {
        val page = getPage(pageIndex) ?: throw IllegalStateException("Cannot open page $pageIndex")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        page.renderPageBitmap(bitmap, 0, 0, width, height, renderAnnot = true)
        return bitmap
    }

    /**
     * Render a tile (visible portion) of a page at full zoom resolution.
     *
     * @param pageIndex page to render
     * @param bitmapWidth output bitmap width (screen width)
     * @param bitmapHeight output bitmap height (screen height)
     * @param fullPageWidth full page width at current zoom in pixels
     * @param fullPageHeight full page height at current zoom in pixels
     * @param offsetX horizontal offset into the full-zoom page (pixels from left)
     * @param offsetY vertical offset into the full-zoom page (pixels from top)
     */
    fun renderTile(
        pageIndex: Int,
        bitmapWidth: Int,
        bitmapHeight: Int,
        fullPageWidth: Int,
        fullPageHeight: Int,
        offsetX: Int,
        offsetY: Int
    ): Bitmap {
        val page = getPage(pageIndex) ?: throw IllegalStateException("Cannot open page $pageIndex")
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        // startX/startY = negative offset positions the page so only the tile area is rendered
        page.renderPageBitmap(bitmap, -offsetX, -offsetY, fullPageWidth, fullPageHeight, renderAnnot = true)
        return bitmap
    }

    // ── Text APIs ────────────────────────────────────────────────────────────

    /**
     * Get a PdfTextPage for text operations on [pageIndex].
     */
    fun getTextPage(pageIndex: Int): PdfTextPage? {
        openTextPages[pageIndex]?.let { return it }
        val page = getPage(pageIndex) ?: return null
        return try {
            val tp = page.openTextPage()
            openTextPages[pageIndex] = tp
            tp
        } catch (_: Exception) { null }
    }

    /**
     * Extract all text from a page.
     */
    fun getPageText(pageIndex: Int): String {
        val tp = getTextPage(pageIndex) ?: return ""
        val count = tp.textPageCountChars()
        if (count <= 0) return ""
        return tp.textPageGetText(0, count) ?: ""
    }

    /**
     * Get character count on a page.
     */
    fun getCharCount(pageIndex: Int): Int {
        val tp = getTextPage(pageIndex) ?: return 0
        return tp.textPageCountChars()
    }

    /**
     * Get the bounding box of a single character in PDF page coordinates.
     */
    fun getCharBox(pageIndex: Int, charIndex: Int): RectF? {
        val tp = getTextPage(pageIndex) ?: return null
        return tp.textPageGetCharBox(charIndex)
    }

    /**
     * Search for [query] on [pageIndex]. Returns list of (startCharIndex, charCount) pairs.
     */
    fun searchPage(pageIndex: Int, query: String): List<Pair<Int, Int>> {
        val tp = getTextPage(pageIndex) ?: return emptyList()
        val results = mutableListOf<Pair<Int, Int>>()
        try {
            val findResult = tp.findStart(query, emptySet(), 0) ?: return emptyList()
            findResult.use { fr ->
                while (fr.findNext()) {
                    val idx = fr.getSchResultIndex()
                    val count = fr.getSchCount()
                    if (idx >= 0 && count > 0) {
                        results.add(idx to count)
                    }
                }
            }
        } catch (_: Exception) {}
        return results
    }

    /**
     * Get bounding rectangles for a range of characters on a page.
     * Returns RectF in PDF page coordinates.
     */
    fun getTextRects(pageIndex: Int, charIndex: Int, charCount: Int): List<RectF> {
        val tp = getTextPage(pageIndex) ?: return emptyList()
        val rects = mutableListOf<RectF>()
        try {
            val rectCount = tp.textPageCountRects(charIndex, charCount)
            for (i in 0 until rectCount) {
                val rect = tp.textPageGetRect(i)
                if (rect != null) {
                    rects.add(rect)
                }
            }
        } catch (_: Exception) {}
        return rects
    }

    /**
     * Map a PDF page-coordinate rectangle to device (pixel) coordinates.
     * [bitmapWidth] and [bitmapHeight] are the rendered bitmap dimensions.
     */
    fun mapRectToDevice(
        pageIndex: Int,
        bitmapWidth: Int,
        bitmapHeight: Int,
        rect: RectF
    ): android.graphics.Rect? {
        val page = getPage(pageIndex) ?: return null
        return try {
            page.mapRectToDevice(0, 0, bitmapWidth, bitmapHeight, 0, rect)
        } catch (_: Exception) { null }
    }

    /**
     * Get character index at a device (pixel) position on the rendered bitmap.
     */
    fun getCharIndexAtPos(
        pageIndex: Int,
        bitmapWidth: Int,
        bitmapHeight: Int,
        deviceX: Int,
        deviceY: Int,
        toleranceX: Double = 10.0,
        toleranceY: Double = 10.0
    ): Int {
        val page = getPage(pageIndex) ?: return -1
        val tp = getTextPage(pageIndex) ?: return -1
        return try {
            // Map device coords to page coords
            val pagePoint = page.mapDeviceCoordsToPage(
                0, 0, bitmapWidth, bitmapHeight, 0, deviceX, deviceY
            )
            tp.textPageGetCharIndexAtPos(
                pagePoint.x.toDouble(), pagePoint.y.toDouble(),
                toleranceX, toleranceY
            )
        } catch (_: Exception) { -1 }
    }

    /**
     * Get the normalized Y center (0..1) of a specific match on a page.
     * 0 = top of page, 1 = bottom of page.
     * Uses a large device size for accurate coordinate mapping.
     */
    fun getMatchNormalizedY(pageIndex: Int, query: String, matchIndex: Int): Float {
        val matches = searchPage(pageIndex, query)
        if (matchIndex !in matches.indices) return 0.5f

        val (charIdx, charCount) = matches[matchIndex]
        val rects = getTextRects(pageIndex, charIdx, charCount)
        if (rects.isEmpty()) return 0.5f

        val page = getPage(pageIndex) ?: return 0.5f

        // Use a fixed large device size for consistent mapping
        val devW = 10000
        val devH = 10000
        val deviceRect = try {
            page.mapRectToDevice(0, 0, devW, devH, 0, rects[0])
        } catch (_: Exception) { null }

        return if (deviceRect != null) {
            val centerY = (deviceRect.top + deviceRect.bottom) / 2f
            (centerY / devH).coerceIn(0f, 1f)
        } else {
            0.5f
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun getPage(pageIndex: Int): PdfPage? {
        openPages[pageIndex]?.let { return it }
        val doc = document ?: return null
        return try {
            val page = doc.openPage(pageIndex)
            openPages[pageIndex] = page
            page
        } catch (_: Exception) { null }
    }

    fun close() {
        openTextPages.values.forEach { try { it.close() } catch (_: Exception) {} }
        openTextPages.clear()
        openPages.values.forEach { try { it.close() } catch (_: Exception) {} }
        openPages.clear()
        document?.close()
        document = null
        fileDescriptor?.close()
        fileDescriptor = null
    }
}
