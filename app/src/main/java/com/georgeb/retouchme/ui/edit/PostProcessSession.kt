package com.georgeb.retouchme.ui.edit

import android.graphics.Bitmap
import com.georgeb.retouchme.face.PostProcessAdjustments

object PostProcessSession {
    var bitmap: Bitmap? = null
    var mask: Bitmap? = null
    var onApplied: ((Bitmap) -> Unit)? = null
    var lastAdjustments: PostProcessAdjustments = PostProcessAdjustments()

    fun start(bitmap: Bitmap, mask: Bitmap?, onApplied: (Bitmap) -> Unit) {
        this.bitmap = bitmap
        this.mask = mask
        this.onApplied = onApplied
    }

    fun clear() {
        bitmap = null
        mask?.recycle()
        mask = null
        onApplied = null
    }
}
