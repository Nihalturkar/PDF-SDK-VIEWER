package com.pdfviewer.sdk.renderer

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.pdfviewer.sdk.cache.BitmapCache
import com.pdfviewer.sdk.core.PdfConfig
import com.pdfviewer.sdk.core.PdfDocument
import io.legere.pdfiumandroid.PdfiumCore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * High-level renderer that coordinates [PdfDocument] and [BitmapCache].
 *
 * All rendering happens on [Dispatchers.IO] via coroutines; the public API is safe to call
 * from any thread. A [Mutex] serialises access to PdfiumCore (which is not thread-safe).
 */
class PdfRenderer(
    context: Context,
    private val config: PdfConfig = PdfConfig()
) {
    private val pdfiumCore = PdfiumCore()
    internal val pdfDocument = PdfDocument(pdfiumCore)

    val cache: BitmapCache = run {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val maxHeapMb = am.memoryClass
        val safeCacheMb = (maxHeapMb / 4).coerceIn(32, config.cacheSizeMb)
        BitmapCache(safeCacheMb)
    }

    private val renderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private var pageSizes: List<Pair<Int, Int>> = emptyList()
    val pageCount: Int get() = pageSizes.size

    // ── Opening ──────────────────────────────────────────────────────────────

    suspend fun openFile(file: File): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            cache.clear()
            pdfDocument.open(file)
            val count = pdfDocument.pageCount
            pageSizes = (0 until count).map { pdfDocument.getPageSize(it) }
            count
        }
    }

    suspend fun openBytes(data: ByteArray): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            cache.clear()
            pdfDocument.open(data)
            val count = pdfDocument.pageCount
            pageSizes = (0 until count).map { pdfDocument.getPageSize(it) }
            count
        }
    }

    // ── Page metrics ─────────────────────────────────────────────────────────

    fun getPageAspectRatio(pageIndex: Int): Float {
        if (pageIndex !in pageSizes.indices) return 210f / 297f
        val (w, h) = pageSizes[pageIndex]
        return if (h > 0) w.toFloat() / h else 210f / 297f
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    suspend fun renderPage(pageIndex: Int, width: Int, height: Int): Bitmap? {
        cache.get(pageIndex, width)?.let { return it }

        return withContext(Dispatchers.IO) {
            mutex.withLock {
                cache.get(pageIndex, width)?.let { return@withContext it }
                try {
                    val bmp = pdfDocument.renderPage(pageIndex, width, height)
                    cache.put(pageIndex, width, bmp)
                    bmp
                } catch (e: OutOfMemoryError) {
                    cache.clear()
                    try {
                        val halfW = (width / 2).coerceAtLeast(1)
                        val halfH = (height / 2).coerceAtLeast(1)
                        val bmp = pdfDocument.renderPage(pageIndex, halfW, halfH)
                        cache.put(pageIndex, halfW, bmp)
                        bmp
                    } catch (_: Throwable) { null }
                } catch (_: Exception) { null }
            }
        }
    }

    fun renderPageAsync(
        pageIndex: Int,
        width: Int,
        height: Int,
        onRendered: (Bitmap?) -> Unit
    ): Job = renderScope.launch {
        val bmp = renderPage(pageIndex, width, height)
        withContext(Dispatchers.Main) { onRendered(bmp) }
    }

    /**
     * Render a tile of a page at full zoom resolution.
     * Returns a screen-sized bitmap containing only the visible portion.
     */
    suspend fun renderTile(
        pageIndex: Int,
        bitmapWidth: Int,
        bitmapHeight: Int,
        fullPageWidth: Int,
        fullPageHeight: Int,
        offsetX: Int,
        offsetY: Int
    ): Bitmap? {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    pdfDocument.renderTile(
                        pageIndex, bitmapWidth, bitmapHeight,
                        fullPageWidth, fullPageHeight, offsetX, offsetY
                    )
                } catch (_: OutOfMemoryError) {
                    cache.clear()
                    null
                } catch (_: Exception) { null }
            }
        }
    }

    fun renderTileAsync(
        pageIndex: Int,
        bitmapWidth: Int,
        bitmapHeight: Int,
        fullPageWidth: Int,
        fullPageHeight: Int,
        offsetX: Int,
        offsetY: Int,
        onRendered: (Bitmap?) -> Unit
    ): Job = renderScope.launch {
        val bmp = renderTile(
            pageIndex, bitmapWidth, bitmapHeight,
            fullPageWidth, fullPageHeight, offsetX, offsetY
        )
        withContext(Dispatchers.Main) { onRendered(bmp) }
    }

    fun prefetchAround(centerPage: Int, width: Int, height: Int, radius: Int = 2) {
        val start = (centerPage - radius).coerceAtLeast(0)
        val end = (centerPage + radius).coerceAtMost(pageCount - 1)
        for (page in start..end) {
            if (cache.get(page, width) != null) continue
            renderScope.launch { renderPage(page, width, height) }
        }
    }

    // ── Text operations (delegated to PdfDocument) ──────────────────────────

    /**
     * Search for [query] on [pageIndex] using native PDFium text search.
     * Returns list of (startCharIndex, charCount) pairs.
     */
    suspend fun searchPage(pageIndex: Int, query: String): List<Pair<Int, Int>> =
        withContext(Dispatchers.IO) {
            mutex.withLock { pdfDocument.searchPage(pageIndex, query) }
        }

    /**
     * Search all pages for [query]. Returns map of pageIndex → list of (charIndex, charCount).
     */
    suspend fun searchAll(query: String): Map<Int, List<Pair<Int, Int>>> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val results = mutableMapOf<Int, List<Pair<Int, Int>>>()
                for (page in 0 until pageCount) {
                    val matches = pdfDocument.searchPage(page, query)
                    if (matches.isNotEmpty()) results[page] = matches
                }
                results
            }
        }

    /**
     * Get highlight rectangles for search matches on [pageIndex], normalized to 0..1.
     * [bitmapWidth] and [bitmapHeight] are the rendered bitmap dimensions.
     */
    suspend fun getHighlightRects(
        pageIndex: Int,
        query: String,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): List<HighlightRect> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val matches = pdfDocument.searchPage(pageIndex, query)
            if (matches.isEmpty()) return@withContext emptyList()

            val rects = mutableListOf<HighlightRect>()
            for ((charIdx, charCount) in matches) {
                val textRects = pdfDocument.getTextRects(pageIndex, charIdx, charCount)
                for (pdfRect in textRects) {
                    val deviceRect = pdfDocument.mapRectToDevice(
                        pageIndex, bitmapWidth, bitmapHeight, pdfRect
                    ) ?: continue

                    // Normalize to 0..1
                    val left = (deviceRect.left.toFloat() / bitmapWidth).coerceIn(0f, 1f)
                    val top = (deviceRect.top.toFloat() / bitmapHeight).coerceIn(0f, 1f)
                    val right = (deviceRect.right.toFloat() / bitmapWidth).coerceIn(0f, 1f)
                    val bottom = (deviceRect.bottom.toFloat() / bitmapHeight).coerceIn(0f, 1f)

                    // Ensure valid rect (top < bottom, left < right)
                    val normLeft = minOf(left, right)
                    val normTop = minOf(top, bottom)
                    val normRight = maxOf(left, right)
                    val normBottom = maxOf(top, bottom)

                    if (normRight > normLeft && normBottom > normTop) {
                        rects.add(HighlightRect(
                            left = (normLeft - 0.002f).coerceAtLeast(0f),
                            top = (normTop - 0.003f).coerceAtLeast(0f),
                            right = (normRight + 0.002f).coerceAtMost(1f),
                            bottom = (normBottom + 0.003f).coerceAtMost(1f)
                        ))
                    }
                }
            }
            rects
        }
    }

    /**
     * Get highlight rects for a specific match index on a page.
     * [matchIndex] is 0-based among matches on that page.
     */
    suspend fun getMatchRects(
        pageIndex: Int,
        query: String,
        matchIndex: Int,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): List<HighlightRect> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val matches = pdfDocument.searchPage(pageIndex, query)
            if (matchIndex !in matches.indices) return@withContext emptyList()

            val (charIdx, charCount) = matches[matchIndex]
            val textRects = pdfDocument.getTextRects(pageIndex, charIdx, charCount)
            val rects = mutableListOf<HighlightRect>()
            for (pdfRect in textRects) {
                val deviceRect = pdfDocument.mapRectToDevice(
                    pageIndex, bitmapWidth, bitmapHeight, pdfRect
                ) ?: continue
                val left = (deviceRect.left.toFloat() / bitmapWidth).coerceIn(0f, 1f)
                val top = (deviceRect.top.toFloat() / bitmapHeight).coerceIn(0f, 1f)
                val right = (deviceRect.right.toFloat() / bitmapWidth).coerceIn(0f, 1f)
                val bottom = (deviceRect.bottom.toFloat() / bitmapHeight).coerceIn(0f, 1f)
                val normLeft = minOf(left, right)
                val normTop = minOf(top, bottom)
                val normRight = maxOf(left, right)
                val normBottom = maxOf(top, bottom)
                if (normRight > normLeft && normBottom > normTop) {
                    rects.add(HighlightRect(
                        left = (normLeft - 0.002f).coerceAtLeast(0f),
                        top = (normTop - 0.003f).coerceAtLeast(0f),
                        right = (normRight + 0.002f).coerceAtMost(1f),
                        bottom = (normBottom + 0.003f).coerceAtMost(1f)
                    ))
                }
            }
            rects
        }
    }

    /**
     * Get normalized Y position (0..1) of a match on a page.
     * Uses PDF native coordinates — no render bitmap dependency.
     */
    suspend fun getMatchNormalizedY(pageIndex: Int, query: String, matchIndex: Int): Float =
        withContext(Dispatchers.IO) {
            mutex.withLock { pdfDocument.getMatchNormalizedY(pageIndex, query, matchIndex) }
        }

    /**
     * Get full text of a page.
     */
    suspend fun getPageText(pageIndex: Int): String = withContext(Dispatchers.IO) {
        mutex.withLock { pdfDocument.getPageText(pageIndex) }
    }

    /**
     * Get character index at a tap position on the rendered bitmap.
     */
    suspend fun getCharIndexAtPos(
        pageIndex: Int,
        bitmapWidth: Int,
        bitmapHeight: Int,
        deviceX: Int,
        deviceY: Int
    ): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            pdfDocument.getCharIndexAtPos(pageIndex, bitmapWidth, bitmapHeight, deviceX, deviceY)
        }
    }

    /**
     * Get text in a character range.
     */
    suspend fun getTextRange(pageIndex: Int, startChar: Int, count: Int): String =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val tp = pdfDocument.getTextPage(pageIndex) ?: return@withContext ""
                tp.textPageGetText(startChar, count) ?: ""
            }
        }

    /**
     * Find word boundaries around a character index.
     * Returns (wordStart, wordEnd) inclusive char indices.
     */
    suspend fun getWordBounds(pageIndex: Int, charIndex: Int): Pair<Int, Int> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val text = pdfDocument.getPageText(pageIndex)
                if (text.isEmpty() || charIndex < 0 || charIndex >= text.length) {
                    return@withContext charIndex to charIndex
                }
                // Expand left to find word start
                var start = charIndex
                while (start > 0 && !text[start - 1].isWhitespace()) start--
                // Expand right to find word end
                var end = charIndex
                while (end < text.length - 1 && !text[end + 1].isWhitespace()) end++
                start to end
            }
        }

    /**
     * Get highlight rects for a selection range (startChar..endChar) on a page.
     * Returns normalized 0..1 rects.
     */
    suspend fun getSelectionRects(
        pageIndex: Int,
        startChar: Int,
        endChar: Int,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): List<HighlightRect> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val first = minOf(startChar, endChar)
            val last = maxOf(startChar, endChar)
            val count = last - first + 1
            val textRects = pdfDocument.getTextRects(pageIndex, first, count)
            val rects = mutableListOf<HighlightRect>()
            for (pdfRect in textRects) {
                val deviceRect = pdfDocument.mapRectToDevice(
                    pageIndex, bitmapWidth, bitmapHeight, pdfRect
                ) ?: continue
                val left = (deviceRect.left.toFloat() / bitmapWidth).coerceIn(0f, 1f)
                val top = (deviceRect.top.toFloat() / bitmapHeight).coerceIn(0f, 1f)
                val right = (deviceRect.right.toFloat() / bitmapWidth).coerceIn(0f, 1f)
                val bottom = (deviceRect.bottom.toFloat() / bitmapHeight).coerceIn(0f, 1f)
                val normLeft = minOf(left, right)
                val normTop = minOf(top, bottom)
                val normRight = maxOf(left, right)
                val normBottom = maxOf(top, bottom)
                if (normRight > normLeft && normBottom > normTop) {
                    rects.add(HighlightRect(normLeft, normTop, normRight, normBottom))
                }
            }
            rects
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    fun close() {
        renderScope.cancel()
        cache.clear()
        pdfDocument.close()
    }

    data class HighlightRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )
}
