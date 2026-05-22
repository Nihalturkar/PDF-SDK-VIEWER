package com.pdfviewer.sdk.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.recyclerview.widget.RecyclerView
import com.pdfviewer.sdk.R
import com.pdfviewer.sdk.core.PdfConfig
import com.pdfviewer.sdk.renderer.PdfRenderer
import kotlinx.coroutines.*

internal class PdfPageAdapter(
    private val renderer: PdfRenderer,
    private val config: PdfConfig,
    private val onTextSelected: (pageIndex: Int, startChar: Int, endChar: Int) -> Unit = { _, _, _ -> }
) : RecyclerView.Adapter<PdfPageAdapter.ViewHolder>() {

    var scaleFactor: Float = 1.0f
    var searchQuery: String = ""
    var recyclerViewHeight: Int = 0

    var currentMatchPage: Int = -1
    var currentMatchOnPage: Int = -1

    private val highlightScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun getItemCount(): Int = renderer.pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pdf_page, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(position)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelRender()
    }

    fun reRenderVisiblePages(recyclerView: RecyclerView) {
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i) ?: continue
            val holder = recyclerView.getChildViewHolder(child) as? ViewHolder ?: continue
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) holder.renderHighRes(pos)
        }
    }

    fun refreshHighlights(recyclerView: RecyclerView) {
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i) ?: continue
            val holder = recyclerView.getChildViewHolder(child) as? ViewHolder ?: continue
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) holder.updateHighlights(pos)
        }
    }

    fun cancelAll() {
        highlightScope.coroutineContext.cancelChildren()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val pageImage: ImageView = itemView.findViewById(R.id.pageImage) as ImageView
        private val tileImage: ImageView = itemView.findViewById(R.id.tileImage)
        private val pageLoading: ProgressBar = itemView.findViewById(R.id.pageLoading)
        private val highlightView: HighlightView = itemView.findViewById(R.id.highlightView)
        private val textSelectionView: TextSelectionView = itemView.findViewById(R.id.textSelectionView)
        private var renderJob: Job? = null
        private var highlightJob: Job? = null

        fun bind(pageIndex: Int) {
            cancelRender()
            pageImage.setImageBitmap(null)
            tileImage.visibility = View.GONE
            pageLoading.visibility = View.GONE

            val viewWidth = itemView.width.takeIf { it > 0 }
                ?: itemView.resources.displayMetrics.widthPixels
            val aspectRatio = renderer.getPageAspectRatio(pageIndex)

            // Always render at max cap — 4096px gives sharpest text at any zoom
            val renderWidth = (viewWidth * config.renderQuality * scaleFactor).toInt()
                .coerceIn(1, 4096)
            val renderHeight = (renderWidth / aspectRatio).toInt().coerceAtLeast(1)
            val displayHeight = (viewWidth / aspectRatio).toInt()

            pageImage.layoutParams.height = displayHeight
            highlightView.layoutParams.height = displayHeight
            textSelectionView.layoutParams.height = displayHeight

            if (renderer.pageCount == 1 && recyclerViewHeight > 0 && displayHeight < recyclerViewHeight) {
                val topPadding = (recyclerViewHeight - displayHeight) / 2
                itemView.setPadding(0, topPadding, 0, 0)
            } else {
                itemView.setPadding(0, 0, 0, 0)
            }

            textSelectionView.renderer = renderer
            textSelectionView.pageIndex = pageIndex
            textSelectionView.renderWidth = renderWidth
            textSelectionView.renderHeight = renderHeight
            textSelectionView.clearSelection()
            textSelectionView.onTextSelected = { page, startChar, endChar ->
                onTextSelected(page, startChar, endChar)
            }

            updateHighlights(pageIndex)

            val cached = renderer.cache.get(pageIndex, renderWidth)
            if (cached != null) {
                pageImage.setImageBitmap(cached)
                return
            }

            renderJob = renderer.renderPageAsync(pageIndex, renderWidth, renderHeight) { bitmap ->
                pageImage.setImageBitmap(bitmap)
            }
        }

        fun updateHighlights(pageIndex: Int) {
            highlightJob?.cancel()
            if (searchQuery.isBlank()) {
                highlightView.highlights = emptyList()
                highlightView.currentMatchHighlights = emptyList()
                return
            }

            val viewWidth = itemView.width.takeIf { it > 0 }
                ?: itemView.resources.displayMetrics.widthPixels
            val aspectRatio = renderer.getPageAspectRatio(pageIndex)
            val renderWidth = (viewWidth * config.renderQuality * scaleFactor).toInt()
                .coerceIn(1, 4096)
            val renderHeight = (renderWidth / aspectRatio).toInt().coerceAtLeast(1)

            highlightJob = highlightScope.launch {
                val rects = renderer.getHighlightRects(
                    pageIndex, searchQuery, renderWidth, renderHeight
                )
                highlightView.highlights = rects

                if (pageIndex == currentMatchPage && currentMatchOnPage >= 0) {
                    val currentRects = renderer.getMatchRects(
                        pageIndex, searchQuery, currentMatchOnPage,
                        renderWidth, renderHeight
                    )
                    highlightView.currentMatchHighlights = currentRects
                } else {
                    highlightView.currentMatchHighlights = emptyList()
                }
            }
        }

        fun renderHighRes(pageIndex: Int) {
            cancelRender()
            val viewWidth = itemView.width.takeIf { it > 0 }
                ?: itemView.resources.displayMetrics.widthPixels
            val aspectRatio = renderer.getPageAspectRatio(pageIndex)
            val renderWidth = (viewWidth * config.renderQuality * scaleFactor).toInt()
                .coerceIn(1, 4096)
            val renderHeight = (renderWidth / aspectRatio).toInt().coerceAtLeast(1)

            textSelectionView.renderWidth = renderWidth
            textSelectionView.renderHeight = renderHeight

            tileImage.visibility = View.GONE

            val cached = renderer.cache.get(pageIndex, renderWidth)
            if (cached != null) {
                pageImage.setImageBitmap(cached)
                return
            }
            renderJob = renderer.renderPageAsync(pageIndex, renderWidth, renderHeight) { bitmap ->
                pageImage.setImageBitmap(bitmap)
            }
        }

        fun cancelRender() {
            renderJob?.cancel()
            renderJob = null
            highlightJob?.cancel()
            highlightJob = null
        }
    }
}
