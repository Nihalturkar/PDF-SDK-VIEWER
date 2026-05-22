package com.pdfviewer.sdk.core

import com.pdfviewer.sdk.renderer.PdfRenderer

/**
 * Text extraction and search facade that delegates to PdfRenderer's native PDFium text APIs.
 * No separate library needed — PDFium handles everything natively.
 */
class PdfTextExtractor(private val renderer: PdfRenderer) {

    val isReady: Boolean get() = renderer.pageCount > 0

    /**
     * Search [query] across all pages (native PDFium search).
     * Returns map of pageIndex → total match count on that page.
     */
    suspend fun search(query: String): Map<Int, Int> {
        if (query.isBlank() || !isReady) return emptyMap()
        val allResults = renderer.searchAll(query)
        return allResults.mapValues { (_, matches) -> matches.size }
    }

    /**
     * Get highlight rectangles for all matches of [query] on [pageIndex].
     * Rectangles are in normalized coordinates (0..1).
     */
    suspend fun getHighlightRects(
        pageIndex: Int,
        query: String,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): List<PdfRenderer.HighlightRect> {
        if (query.isBlank() || !isReady) return emptyList()
        return renderer.getHighlightRects(pageIndex, query, bitmapWidth, bitmapHeight)
    }

    suspend fun getPageText(pageIndex: Int): String = renderer.getPageText(pageIndex)

    fun clear() {
        // Nothing to clear — text pages are managed by PdfDocument
    }
}
