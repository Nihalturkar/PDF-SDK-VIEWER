package com.pdfviewer.sample

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pdfviewer.sample.databinding.ActivityMainBinding
import com.pdfviewer.sdk.core.PdfListener
import com.pdfviewer.sdk.ui.PDFView
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var pdfView: PDFView

    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { openPdfFromUri(it) }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                openDocumentLauncher.launch(arrayOf("application/pdf"))
            } else {
                Toast.makeText(this, "Storage permission required", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pdfView = binding.pdfView

        setupListener()
        setupButtons()
    }

    override fun onStop() {
        super.onStop()
        // Free bitmap cache when app goes to background
        pdfView.trimMemory()
    }

    override fun onDestroy() {
        pdfView.close()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_MODERATE) {
            pdfView.trimMemory()
        }
    }

    private fun setupListener() {
        pdfView.setPdfListener(object : PdfListener {
            override fun onPageChanged(page: Int) {
                binding.pageIndicator.text = "Page: ${page + 1} / ${pdfView.getPageCount()}"
            }

            override fun onLoadComplete(totalPages: Int) {
                binding.pageIndicator.text = "Page: 1 / $totalPages"
                Toast.makeText(this@MainActivity, "Loaded $totalPages pages", Toast.LENGTH_SHORT).show()
            }

            override fun onZoomChanged(scale: Float) {
                binding.zoomIndicator.text = "${(scale * 100).toInt()}%"
            }

            override fun onError(error: Throwable) {
                Toast.makeText(this@MainActivity, "Error: ${error.message}", Toast.LENGTH_LONG).show()
            }

            override fun onSearchResults(totalMatches: Int) {
                // Nav buttons are now inside PDFView's search bar — nothing to do here
            }
        })
    }

    private fun setupButtons() {
        binding.btnOpen.setOnClickListener { requestFileOpen() }
        binding.btnZoomIn.setOnClickListener { pdfView.zoomIn() }
        binding.btnZoomOut.setOnClickListener { pdfView.zoomOut() }
        binding.btnRotate.setOnClickListener { toggleOrientation() }

        // Search nav buttons are now inside PDFView's search bar
    }

    private fun openPdfFromUri(uri: Uri) {
        try {
            val tempFile = File(cacheDir, "temp_pdf_${System.currentTimeMillis()}.pdf")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            pdfView.openPdf(tempFile)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to open PDF: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleOrientation() {
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun requestFileOpen() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                return
            }
        }
        openDocumentLauncher.launch(arrayOf("application/pdf"))
    }
}
