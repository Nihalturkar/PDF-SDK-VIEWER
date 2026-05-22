package com.pdfviewer.sdk.core

/**
 * Immutable configuration for the PDF viewer.
 *
 * @property maxZoom          Maximum zoom scale (default 10× = 1000%).
 * @property minZoom          Minimum zoom scale (default 0.5× = 50%).
 * @property doubleTapZoom    Zoom scale applied on double-tap (default 2.5×).
 * @property cacheSizeMb      Maximum bitmap cache size in megabytes (default 192 MB).
 * @property pageSpacingDp    Vertical spacing between rendered pages in dp (default 8 dp).
 * @property renderQuality    Base multiplier for bitmap resolution at 1× zoom (default 2.0×).
 */
data class PdfConfig(
    val maxZoom: Float = 30.0f,
    val minZoom: Float = 1.0f,
    val doubleTapZoom: Float = 2.5f,
    val cacheSizeMb: Int = 128,
    val pageSpacingDp: Int = 8,
    val renderQuality: Float = 3.0f
) {
    init {
        require(minZoom > 0f) { "minZoom must be > 0" }
        require(maxZoom >= minZoom) { "maxZoom must be >= minZoom" }
        require(doubleTapZoom in minZoom..maxZoom) { "doubleTapZoom must be between minZoom and maxZoom" }
        require(cacheSizeMb > 0) { "cacheSizeMb must be > 0" }
        require(renderQuality > 0f) { "renderQuality must be > 0" }
    }
}
