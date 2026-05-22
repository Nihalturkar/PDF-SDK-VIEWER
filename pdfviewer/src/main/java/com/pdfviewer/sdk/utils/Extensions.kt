package com.pdfviewer.sdk.utils

import android.content.Context
import android.util.TypedValue
import kotlin.math.roundToInt

/** Convert dp to pixels. */
internal fun Context.dpToPx(dp: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics)
        .roundToInt()

/** Clamp a float between [min] and [max]. */
internal fun Float.clamp(min: Float, max: Float): Float = coerceIn(min, max)
