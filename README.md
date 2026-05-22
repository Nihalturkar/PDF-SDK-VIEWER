# PDF Viewer SDK for Android

A lightweight, production-ready Android PDF Viewer SDK built with Kotlin, PdfiumAndroid, Coroutines, and RecyclerView.

## Features

- Open PDF from file, InputStream, or byte array
- RecyclerView-based page rendering (only visible pages rendered)
- Pinch-to-zoom with ScaleGestureDetector
- Double-tap zoom toggle
- Programmatic zoom in / out / reset
- LruCache-based bitmap caching (configurable size)
- Coroutine-based background rendering (zero UI blocking)
- Automatic bitmap recycling and memory management
- Page change / load / zoom listener callbacks
- Android 8.0+ (API 26+)
- Clean modular architecture

## Project Structure

```
pdf-sdk/
├── pdfviewer/                    # SDK library module (.aar)
│   └── src/main/java/com/pdfviewer/sdk/
│       ├── core/
│       │   ├── PdfListener.kt    # Event callback interface
│       │   ├── PdfConfig.kt      # Immutable configuration
│       │   └── PdfDocument.kt    # PdfiumCore wrapper
│       ├── renderer/
│       │   └── PdfRenderer.kt    # Async render coordinator
│       ├── cache/
│       │   └── BitmapCache.kt    # LruCache<Int, Bitmap>
│       ├── gestures/
│       │   └── PdfGestureHandler.kt  # Pinch + double-tap zoom
│       ├── ui/
│       │   ├── PDFView.kt        # Public FrameLayout component
│       │   └── PdfPageAdapter.kt # RecyclerView adapter
│       └── utils/
│           └── Extensions.kt     # dp→px, clamp helpers
│
└── sample-app/                   # Demo application
    └── src/main/java/com/pdfviewer/sample/
        └── MainActivity.kt
```

## Quick Start

### 1. Add the SDK to your project

Copy the `pdfviewer` module into your project and add to `settings.gradle.kts`:

```kotlin
include(":pdfviewer")
```

Add the dependency in your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":pdfviewer"))
}
```

### 2. Add PDFView to your layout

```xml
<com.pdfviewer.sdk.ui.PDFView
    android:id="@+id/pdfView"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

### 3. Open a PDF

```kotlin
val pdfView = findViewById<PDFView>(R.id.pdfView)

// From a File
pdfView.openPdf(file)

// From assets
pdfView.openPdf(assets.open("document.pdf"))

// From byte array
pdfView.openPdf(byteArray)
```

### 4. Listen for events

```kotlin
pdfView.setPdfListener(object : PdfListener {
    override fun onPageChanged(page: Int) { /* 0-based */ }
    override fun onLoadComplete(totalPages: Int) { }
    override fun onZoomChanged(scale: Float) { }
    override fun onError(error: Throwable) { }
})
```

### 5. Programmatic controls

```kotlin
pdfView.zoomIn()
pdfView.zoomOut()
pdfView.resetZoom()
pdfView.goToPage(2)
pdfView.getPageCount()
pdfView.getCurrentPage()
pdfView.getCurrentZoom()
```

### 6. Custom configuration

```kotlin
pdfView.setConfig(PdfConfig(
    maxZoom = 5.0f,
    minZoom = 1.0f,
    doubleTapZoom = 2.5f,
    cacheSizeMb = 64,
    pageSpacingDp = 8,
    renderQuality = 1.5f
))
```

### 7. Cleanup

```kotlin
override fun onDestroy() {
    pdfView.close()
    super.onDestroy()
}
```

## Build Commands

All commands should be run from the project root (`pdf-sdk/`).

### Build the SDK (AAR)

```bash
# Debug AAR
./gradlew :pdfviewer:assembleDebug

# Release AAR
./gradlew :pdfviewer:assembleRelease

# Output: pdfviewer/build/outputs/aar/pdfviewer-release.aar
```

### Build & install the sample app

```bash
# Build debug APK
./gradlew :sample-app:assembleDebug

# Install on connected device (USB debugging must be enabled)
./gradlew :sample-app:installDebug

# Build + install + launch in one step
./gradlew :sample-app:installDebug && adb shell am start -n com.pdfviewer.sample/.MainActivity
```

### Clean & rebuild

```bash
./gradlew clean
./gradlew build
```

### Windows-specific (if gradlew not executable)

```cmd
gradlew.bat :pdfviewer:assembleRelease
gradlew.bat :sample-app:installDebug
```

## Requirements

- Android Studio Hedgehog (2023.1.1) or later
- Gradle 8.5+
- Kotlin 1.9.22+
- minSdk 26 (Android 8.0 Oreo)
- compileSdk 34

## Dependencies

| Library | Purpose |
|---------|---------|
| PdfiumAndroid | Native PDF rendering engine |
| kotlinx-coroutines | Background rendering |
| androidx.recyclerview | Virtualised page list |
| androidx.lifecycle | Coroutine scope management |

## License

MIT
