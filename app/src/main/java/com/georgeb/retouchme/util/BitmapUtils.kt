package com.georgeb.retouchme.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlin.math.max

object BitmapUtils {

    private const val MAX_DIMENSION = 1920

    fun loadBitmap(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        val sampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun calculateSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sampleSize = 1
        val largest = max(width, height)
        while (largest / sampleSize > maxDim) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
