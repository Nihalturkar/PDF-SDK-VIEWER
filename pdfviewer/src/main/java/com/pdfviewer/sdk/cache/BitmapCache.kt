package com.pdfviewer.sdk.cache

import android.graphics.Bitmap
import android.util.LruCache

/**
 * Thread-safe LRU bitmap cache for rendered PDF pages.
 *
 * Keys are composite: page index + a quality tag (typically the rendered pixel width).
 * This means the same page at different zoom levels is cached separately, so zooming in
 * triggers a fresh high-res render instead of serving a blurry low-res bitmap.
 *
 * Evicted bitmaps are NOT recycled — they may still be displayed in ImageViews.
 * The GC reclaims native memory when all references are dropped.
 *
 * @param maxSizeMb maximum cache size in megabytes.
 */
class BitmapCache(maxSizeMb: Int) {

    /**
     * Cache key combining page index and rendered width.
     * Two renders of the same page at different resolutions get separate entries.
     */
    data class CacheKey(val pageIndex: Int, val renderWidth: Int)

    private val cache: LruCache<CacheKey, Bitmap> =
        object : LruCache<CacheKey, Bitmap>(maxSizeMb * 1024) {

            override fun sizeOf(key: CacheKey, value: Bitmap): Int {
                return value.byteCount / 1024 // size in KB
            }

            // Do NOT recycle bitmaps on eviction — they may still be displayed
            // in an ImageView. Let the GC reclaim native memory when all
            // references are gone. This prevents "Canvas: trying to use a
            // recycled bitmap" crashes when scrolling back.
        }

    /** Retrieve a cached bitmap for [pageIndex] at the given [renderWidth], or null. */
    fun get(pageIndex: Int, renderWidth: Int): Bitmap? {
        return cache.get(CacheKey(pageIndex, renderWidth))
    }

    /** Store a rendered bitmap for [pageIndex] at the given [renderWidth]. */
    fun put(pageIndex: Int, renderWidth: Int, bitmap: Bitmap) {
        cache.put(CacheKey(pageIndex, renderWidth), bitmap)
    }

    /** Evict all cached bitmaps. */
    fun clear() {
        cache.evictAll()
    }

    /** Current cache utilisation in KB. */
    val sizeKb: Int get() = cache.size()

    /** Maximum cache capacity in KB. */
    val maxSizeKb: Int get() = cache.maxSize()
}
