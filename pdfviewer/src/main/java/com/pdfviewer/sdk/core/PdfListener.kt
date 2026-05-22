package com.pdfviewer.sdk.core

/**
 * Callback interface for PDF viewer events.
 * Implement this to receive notifications about page changes, load completion, and zoom level changes.
 */
interface PdfListener {

    /** Called when the currently visible page changes. [page] is 0-based. */
    fun onPageChanged(page: Int)

    /** Called once the PDF has been fully opened. [totalPages] is the document's page count. */
    fun onLoadComplete(totalPages: Int)

    /** Called whenever the zoom scale changes (pinch or double-tap). */
    fun onZoomChanged(scale: Float)

    /** Called when an error occurs during PDF loading or rendering. */
    fun onError(error: Throwable)

    /** Called when search results change. [totalMatches] is 0 when search is cleared. */
    fun onSearchResults(totalMatches: Int) {}
}
