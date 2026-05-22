package com.pdfviewer.sdk.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout

import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pdfviewer.sdk.R
import com.pdfviewer.sdk.core.PdfConfig
import com.pdfviewer.sdk.core.PdfListener
import com.pdfviewer.sdk.core.PdfTextExtractor
import com.pdfviewer.sdk.gestures.ZoomableLayout
import com.pdfviewer.sdk.renderer.PdfRenderer
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream

class PDFView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var config: PdfConfig = PdfConfig()
    private var listener: PdfListener? = null
    private var renderer: PdfRenderer = PdfRenderer(context, config)
    private var textExtractor = PdfTextExtractor(renderer)
    private lateinit var adapter: PdfPageAdapter

    private val recyclerView: RecyclerView
    private val loadingIndicator: ProgressBar
    private val zoomableLayout: ZoomableLayout
    private val searchInput: EditText
    private val searchCount: TextView
    private val btnSearchClose: TextView
    private val btnSearchPrev: TextView
    private val btnSearchNext: TextView
    private val btnSearchInfo: TextView
    private val btnSearchMode: TextView
    private var isExactSearch = true

    private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val scope: CoroutineScope
        get() = findViewTreeLifecycleOwner()?.lifecycleScope ?: fallbackScope

    private var currentPage: Int = -1
    private var isDocumentOpen = false
    private var searchJob: Job? = null
    private var lastSearchResults: Map<Int, Int> = emptyMap()
    private var suppressNavigate = false  // When true, search will NOT jump to first match

    // Flat list of all matches: each entry is (pageIndex, matchIndexOnPage)
    private var allMatches: List<Pair<Int, Int>> = emptyList()
    private var currentMatchIndex: Int = -1
    private var navJob: Job? = null
    private var reRenderJob: Job? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_pdf, this, true)
        recyclerView = findViewById(R.id.pdfRecyclerView)
        loadingIndicator = findViewById(R.id.pdfLoadingIndicator)
        zoomableLayout = findViewById(R.id.zoomableLayout)
        searchInput = findViewById(R.id.searchInput)
        searchCount = findViewById(R.id.searchCount)
        btnSearchClose = findViewById(R.id.btnSearchClose)
        btnSearchPrev = findViewById(R.id.btnSearchPrev)
        btnSearchNext = findViewById(R.id.btnSearchNext)
        btnSearchInfo = findViewById(R.id.btnSearchInfo)
        btnSearchMode = findViewById(R.id.btnSearchMode)

        setupZoomableLayout()
        setupRecyclerView()
        setupSearchBar()
    }

    private fun setupZoomableLayout() {
        zoomableLayout.minScale = config.minZoom
        zoomableLayout.maxScale = config.maxZoom
        zoomableLayout.doubleTapScale = config.doubleTapZoom
        zoomableLayout.onScaleChanged = { scale -> listener?.onZoomChanged(scale) }
        zoomableLayout.onScaleSettled = { scale ->
            adapter.scaleFactor = scale
            // Delay re-render so it doesn't block drag/zoom gestures
            reRenderJob?.cancel()
            reRenderJob = scope.launch {
                delay(300)
                adapter.reRenderVisiblePages(recyclerView)
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = createAdapter()
        recyclerView.apply {
            val lm = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            lm.initialPrefetchItemCount = 4
            layoutManager = lm
            adapter = this@PDFView.adapter
            setHasFixedSize(false)
            setItemViewCacheSize(6)
            viewTreeObserver.addOnGlobalLayoutListener {
                this@PDFView.adapter.recyclerViewHeight = height
            }
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                private var prefetchJob: Job? = null

                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val layoutMgr = rv.layoutManager as? LinearLayoutManager ?: return
                    val first = layoutMgr.findFirstCompletelyVisibleItemPosition()
                        .takeIf { it != RecyclerView.NO_POSITION }
                        ?: layoutMgr.findFirstVisibleItemPosition()
                    if (first != RecyclerView.NO_POSITION && first != currentPage) {
                        currentPage = first
                        listener?.onPageChanged(currentPage)
                    }
                }

                override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        // Prefetch only when scroll stops — not during drag
                        prefetchJob?.cancel()
                        prefetchJob = scope.launch {
                            delay(100)
                            if (currentPage >= 0) prefetchAroundPage(currentPage)
                        }
                    }
                }
            })
        }
    }

    private fun createAdapter() = PdfPageAdapter(
        renderer, config
    ) { pageIndex, startChar, endChar -> copySelectedText(pageIndex, startChar, endChar) }

    private fun setupSearchBar() {
        searchInput.addTextChangedListener(object : TextWatcher {
            private var debounceJob: Job? = null
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                debounceJob?.cancel()
                debounceJob = fallbackScope.launch {
                    delay(300)
                    performSearch(s?.toString() ?: "")
                }
            }
        })

        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch(searchInput.text.toString())
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
                true
            } else false
        }

        btnSearchClose.setOnClickListener { clearSearch() }
        btnSearchPrev.setOnClickListener { goToPrevMatch() }
        btnSearchNext.setOnClickListener { goToNextMatch() }
        btnSearchInfo.setOnClickListener { showSearchInfo() }
        btnSearchMode.setOnClickListener { toggleSearchMode() }
    }

    private fun toggleSearchMode() {
        isExactSearch = !isExactSearch
        btnSearchMode.text = if (isExactSearch) "Aa" else "~"
        btnSearchMode.setTextColor(
            if (isExactSearch) 0xFFFFFFFF.toInt() else 0xFFFFEB3B.toInt()
        )
        // Re-search with new mode
        val q = searchInput.text.toString()
        if (q.isNotBlank()) performSearch(q)
    }

    private fun performSearch(query: String) {
        searchJob?.cancel()
        adapter.searchQuery = query

        if (query.isBlank()) {
            searchCount.text = ""
            lastSearchResults = emptyMap()
            allMatches = emptyList()
            currentMatchIndex = -1
            adapter.currentMatchPage = -1
            adapter.currentMatchOnPage = -1
            updateNavVisibility(false)
            adapter.refreshHighlights(recyclerView)
            listener?.onSearchResults(0)
            return
        }

        if (!isDocumentOpen) {
            searchCount.text = "Loading..."
            return
        }

        searchCount.text = "..."
        searchJob = fallbackScope.launch {
            val results = textExtractor.search(query)
            lastSearchResults = results
            val total = results.values.sum()

            val matches = mutableListOf<Pair<Int, Int>>()
            for ((page, count) in results.toSortedMap()) {
                for (i in 0 until count) {
                    matches.add(page to i)
                }
            }
            allMatches = matches

            val shouldNavigate = !suppressNavigate
            suppressNavigate = false

            if (matches.isNotEmpty()) {
                // Pick closest match to current page (not always first)
                val startIdx = if (!shouldNavigate && currentPage >= 0) {
                    // Find first match on or after current page
                    val idx = matches.indexOfFirst { it.first >= currentPage }
                    if (idx >= 0) idx else 0
                } else {
                    0
                }
                currentMatchIndex = startIdx

                val (page, matchOnPage) = matches[startIdx]
                adapter.currentMatchPage = page
                adapter.currentMatchOnPage = matchOnPage
                searchCount.text = "${startIdx + 1}/$total"
                updateNavVisibility(true)
            } else {
                currentMatchIndex = -1
                adapter.currentMatchPage = -1
                adapter.currentMatchOnPage = -1
                searchCount.text = if (total > 0) "$total found" else "Not found"
                updateNavVisibility(false)
            }

            adapter.refreshHighlights(recyclerView)
            listener?.onSearchResults(total)

            if (matches.isNotEmpty() && shouldNavigate) {
                goToPage(matches[currentMatchIndex].first)
            }
        }
    }

    fun showSearchInfo() {
        if (lastSearchResults.isEmpty()) return

        val items = lastSearchResults.toSortedMap().map { (page, count) ->
            "Page ${page + 1} — $count match${if (count > 1) "es" else ""}"
        }

        AlertDialog.Builder(context)
            .setTitle("Search Results")
            .setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, items)) { _, which ->
                val page = lastSearchResults.toSortedMap().keys.toList()[which]
                val matchIdx = allMatches.indexOfFirst { it.first == page }
                if (matchIdx >= 0) {
                    currentMatchIndex = matchIdx
                    navigateToCurrentMatch()
                } else {
                    goToPage(page)
                }
            }
            .setNegativeButton("Close", null)
            .create()
            .show()
    }

    private fun navigateToCurrentMatch() {
        if (currentMatchIndex < 0 || currentMatchIndex >= allMatches.size) return
        val (page, matchOnPage) = allMatches[currentMatchIndex]

        adapter.currentMatchPage = page
        adapter.currentMatchOnPage = matchOnPage
        searchCount.text = "${currentMatchIndex + 1}/${allMatches.size}"

        navJob?.cancel()
        navJob = fallbackScope.launch {
            val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return@launch

            // Step 0: Reset zoom to 100% so user can clearly see the match
            if (zoomableLayout.currentScale < 0.95f || zoomableLayout.currentScale > 1.05f) {
                zoomableLayout.resetZoomInstant()
                adapter.scaleFactor = 1f
                delay(50)
            }

            // Step 1: Scroll the target page to top of RecyclerView
            lm.scrollToPositionWithOffset(page, 0)
            currentPage = page
            listener?.onPageChanged(currentPage)

            // Step 2: Wait for layout to complete
            delay(150)

            // Step 3: Refresh highlights so currentMatchHighlights gets populated
            adapter.refreshHighlights(recyclerView)

            // Step 4: Wait for highlight coroutine to finish
            delay(150)

            // Step 5: Find the page's view in RecyclerView and read HighlightView position
            val pageView = findPageView(page)
            if (pageView != null) {
                val highlightView = pageView.findViewById<HighlightView>(R.id.highlightView)
                val matchRects = highlightView?.currentMatchHighlights
                if (!matchRects.isNullOrEmpty()) {
                    // Get center Y of the match in pixels relative to the page view
                    val firstRect = matchRects[0]
                    val matchCenterY = ((firstRect.top + firstRect.bottom) / 2f) * highlightView.height

                    // Page view's top position relative to RecyclerView
                    val pageTopInRv = pageView.top

                    // Absolute Y of the match inside the RecyclerView's coordinate space
                    val matchAbsY = pageTopInRv + matchCenterY

                    // Screen center of RecyclerView (zoom is always 1x here)
                    val screenCenter = recyclerView.height / 2f

                    // Delta to scroll so match lands at center
                    val delta = (matchAbsY - screenCenter).toInt()

                    recyclerView.scrollBy(0, delta)
                }
            }
        }
    }

    /**
     * Find the child view for a specific page index in the RecyclerView.
     */
    private fun findPageView(pageIndex: Int): View? {
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i) ?: continue
            val holder = recyclerView.getChildViewHolder(child)
            if (holder.bindingAdapterPosition == pageIndex) return child
        }
        return null
    }

    private fun copySelectedText(pageIndex: Int, startChar: Int, endChar: Int) {
        if (!isDocumentOpen) return

        scope.launch {
            val first = minOf(startChar, endChar)
            val last = maxOf(startChar, endChar)
            val count = last - first + 1
            val text = renderer.getTextRange(pageIndex, first, count).trim()
            if (text.isNotBlank()) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("PDF Text", text))
                Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()

                // Fill search input but DON'T navigate away from current page
                suppressNavigate = true
                searchInput.setText(text)
                searchInput.setSelection(text.length)
            }
        }
    }

    private fun prefetchAroundPage(page: Int) {
        val w = recyclerView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val rw = (w * config.renderQuality).toInt().coerceAtLeast(1)
        val ar = renderer.getPageAspectRatio(page)
        val rh = (rw / ar).toInt().coerceAtLeast(1)
        renderer.prefetchAround(page, rw, rh, radius = 2)
    }

    // ── Public API ───────────────────────────────────────────────────────────

    fun setConfig(c: PdfConfig) { config = c; setupZoomableLayout() }
    fun setPdfListener(l: PdfListener) { listener = l }
    fun getPageCount(): Int = renderer.pageCount
    fun getCurrentPage(): Int = currentPage.coerceAtLeast(0)
    fun getCurrentZoom(): Float = zoomableLayout.currentScale
    fun zoomIn() = zoomableLayout.zoomIn()
    fun zoomOut() = zoomableLayout.zoomOut()
    fun resetZoom() = zoomableLayout.resetZoom()

    fun goToPage(page: Int) {
        if (page in 0 until renderer.pageCount) recyclerView.smoothScrollToPosition(page)
    }

    /** Navigate to next search match. Call from external prev/next buttons. */
    fun goToNextMatch() {
        if (allMatches.isEmpty()) return
        currentMatchIndex = (currentMatchIndex + 1) % allMatches.size
        navigateToCurrentMatch()
    }

    /** Navigate to previous search match. Call from external prev/next buttons. */
    fun goToPrevMatch() {
        if (allMatches.isEmpty()) return
        currentMatchIndex = if (currentMatchIndex <= 0) allMatches.size - 1 else currentMatchIndex - 1
        navigateToCurrentMatch()
    }

    /** Returns true if there are search results to navigate. */
    fun hasSearchResults(): Boolean = allMatches.isNotEmpty()

    private fun updateNavVisibility(show: Boolean) {
        val vis = if (show) View.VISIBLE else View.GONE
        btnSearchPrev.visibility = vis
        btnSearchNext.visibility = vis
        btnSearchInfo.visibility = vis
        btnSearchMode.visibility = vis
    }

    fun clearSearch() {
        searchInput.text.clear()
        searchCount.text = ""
        lastSearchResults = emptyMap()
        allMatches = emptyList()
        currentMatchIndex = -1
        adapter.searchQuery = ""
        adapter.currentMatchPage = -1
        adapter.currentMatchOnPage = -1
        updateNavVisibility(false)
        adapter.refreshHighlights(recyclerView)
        listener?.onSearchResults(0)
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    fun focusSearch() {
        searchInput.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    fun search(query: String, onResult: (totalMatches: Int, pageCount: Int) -> Unit) {
        searchJob?.cancel()
        searchJob = scope.launch {
            val results = textExtractor.search(query)
            lastSearchResults = results
            onResult(results.values.sum(), results.size)
        }
    }

    fun getSearchResults(): Map<Int, Int> = lastSearchResults

    fun getPageText(pageIndex: Int, onResult: (String) -> Unit) {
        scope.launch { onResult(renderer.getPageText(pageIndex)) }
    }

    fun openPdf(file: File) {
        resetForNewDocument()
        loadingIndicator.visibility = View.VISIBLE
        scope.launch {
            try {
                val pages = renderer.openFile(file)
                isDocumentOpen = true
                loadingIndicator.visibility = View.GONE
                adapter.notifyDataSetChanged()
                listener?.onLoadComplete(pages)
                prefetchAroundPage(0)
            } catch (e: Exception) {
                loadingIndicator.visibility = View.GONE
                listener?.onError(e)
            }
        }
    }

    fun openPdf(inputStream: InputStream) {
        resetForNewDocument()
        loadingIndicator.visibility = View.VISIBLE
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { inputStream.readBytes() }
                val pages = renderer.openBytes(bytes)
                isDocumentOpen = true
                loadingIndicator.visibility = View.GONE
                adapter.notifyDataSetChanged()
                listener?.onLoadComplete(pages)
                prefetchAroundPage(0)
            } catch (e: Exception) {
                loadingIndicator.visibility = View.GONE
                listener?.onError(e)
            }
        }
    }

    /**
     * Open a PDF from a URL. Downloads the file in background and then renders it.
     */
    fun openPdf(url: String) {
        resetForNewDocument()
        loadingIndicator.visibility = View.VISIBLE
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    java.net.URL(url).openConnection().apply {
                        connectTimeout = 15000
                        readTimeout = 30000
                    }.getInputStream().readBytes()
                }
                val pages = renderer.openBytes(bytes)
                isDocumentOpen = true
                loadingIndicator.visibility = View.GONE
                adapter.notifyDataSetChanged()
                listener?.onLoadComplete(pages)
                prefetchAroundPage(0)
            } catch (e: Exception) {
                loadingIndicator.visibility = View.GONE
                listener?.onError(e)
            }
        }
    }

    fun openPdf(data: ByteArray) {
        resetForNewDocument()
        loadingIndicator.visibility = View.VISIBLE
        scope.launch {
            try {
                val pages = renderer.openBytes(data)
                isDocumentOpen = true
                loadingIndicator.visibility = View.GONE
                adapter.notifyDataSetChanged()
                listener?.onLoadComplete(pages)
                prefetchAroundPage(0)
            } catch (e: Exception) {
                loadingIndicator.visibility = View.GONE
                listener?.onError(e)
            }
        }
    }

    private fun resetForNewDocument() {
        if (isDocumentOpen) { renderer.close(); isDocumentOpen = false }
        renderer = PdfRenderer(context, config)
        textExtractor = PdfTextExtractor(renderer)
        adapter = createAdapter()
        recyclerView.adapter = adapter
        currentPage = -1
        lastSearchResults = emptyMap()
        allMatches = emptyList()
        currentMatchIndex = -1
        searchJob?.cancel()
        searchInput.text.clear()
        searchCount.text = ""
        listener?.onSearchResults(0)
        zoomableLayout.resetZoom()
    }

    /** Clear bitmap cache to free memory. Call when app goes to background. */
    fun trimMemory() {
        renderer.cache.clear()
    }

    fun close() {
        if (isDocumentOpen) { renderer.close(); isDocumentOpen = false }
        searchJob?.cancel()
        fallbackScope.cancel()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        close()
    }
}
