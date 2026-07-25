package com.example.retake_lite.face

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.pow
import kotlin.math.roundToInt

object PostProcessEngine {

    fun apply(bitmap: Bitmap, mask: Bitmap?, adj: PostProcessAdjustments): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        val msk = if (mask != null && hasFaceAdjustments(adj)) {
            IntArray(w * h).also { mask.getPixels(it, 0, w, 0, 0, w, h) }
        } else null

        bitmap.getPixels(px, 0, w, 0, 0, w, h)

        for (i in px.indices) {
            val inFace = msk != null && Color.alpha(msk[i]) >= 16
            var a = Color.alpha(px[i])
            var r = Color.red(px[i])
            var g = Color.green(px[i])
            var bl = Color.blue(px[i])

            val fBright = if (inFace) adj.faceBrightness else 0f
            val fContr = if (inFace) adj.faceContrast else 0f
            val fSat = if (inFace) adj.faceSaturation else 0f
            val fWarm = if (inFace) adj.faceWarmth else 0f

            val gBright = adj.brightness
            val gContr = adj.contrast
            val gSat = adj.saturation
            val gWarm = adj.warmth
            val gGamma = adj.gamma

            val totalBright = fBright + gBright
            val totalContr = fContr + gContr
            val totalSat = fSat + gSat
            val totalWarm = fWarm + gWarm

            if (totalBright != 0f || totalContr != 0f || gGamma != 1f) {
                var fR = r / 255f
                var fG = g / 255f
                var fB = bl / 255f

                if (totalContr != 0f) {
                    val factor = 1f + totalContr / 100f
                    fR = ((fR - 0.5f) * factor + 0.5f).coerceIn(0f, 1f)
                    fG = ((fG - 0.5f) * factor + 0.5f).coerceIn(0f, 1f)
                    fB = ((fB - 0.5f) * factor + 0.5f).coerceIn(0f, 1f)
                }

                if (gGamma != 1f) {
                    val inv = 1f / gGamma
                    fR = fR.pow(inv)
                    fG = fG.pow(inv)
                    fB = fB.pow(inv)
                }

                if (totalBright != 0f) {
                    val b = totalBright / 100f
                    fR = (fR + b).coerceIn(0f, 1f)
                    fG = (fG + b).coerceIn(0f, 1f)
                    fB = (fB + b).coerceIn(0f, 1f)
                }

                r = (fR * 255).toInt().coerceIn(0, 255)
                g = (fG * 255).toInt().coerceIn(0, 255)
                bl = (fB * 255).toInt().coerceIn(0, 255)
            }

            if (totalSat != 0f) {
                val max = maxOf(r, g, bl)
                val min = minOf(r, g, bl)
                val lum = (max + min) / 2f
                val s = if (max == min) 0f else (max - min).toFloat()
                val factor = 1f + totalSat / 100f
                val newMax = (lum + s * factor / 2f).roundToInt().coerceIn(0, 255)
                val newMin = (lum - s * factor / 2f).roundToInt().coerceIn(0, 255)
                r = if (r == max) newMax else if (r == min) newMin else r
                g = if (g == max) newMax else if (g == min) newMin else g
                bl = if (bl == max) newMax else if (bl == min) newMin else bl
            }

            if (totalWarm != 0f) {
                val warm = totalWarm / 100f
                r = (r + warm * 30).roundToInt().coerceIn(0, 255)
                bl = (bl - warm * 30).roundToInt().coerceIn(0, 255)
            }

            px[i] = Color.argb(a.coerceIn(0, 255), r, g, bl)
        }

        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        out.setPixels(px, 0, w, 0, 0, w, h)
        bitmap.recycle()
        return applySharpness(out, adj.sharpness)
    }

    private fun hasFaceAdjustments(adj: PostProcessAdjustments): Boolean =
        adj.faceBrightness != 0f || adj.faceContrast != 0f ||
        adj.faceSaturation != 0f || adj.faceWarmth != 0f

    private fun applySharpness(bitmap: Bitmap, amount: Float): Bitmap {
        if (amount <= 0f) return bitmap
        val w = bitmap.width
        val h = bitmap.height
        val src = IntArray(w * h)
        bitmap.getPixels(src, 0, w, 0, 0, w, h)
        val blurred = IntArray(w * h)

        val radius = 1
        for (y in radius until h - radius) {
            for (x in radius until w - radius) {
                var ra = 0; var ga = 0; var ba = 0; var c = 0
                for (dy in -radius..radius) for (dx in -radius..radius) {
                    val n = (y + dy) * w + (x + dx)
                    ra += Color.red(src[n]); ga += Color.green(src[n]); ba += Color.blue(src[n]); c++
                }
                val idx = y * w + x
                blurred[idx] = Color.rgb(ra / c, ga / c, ba / c)
            }
        }

        val strength = (amount / 100f).coerceIn(0f, 1f)
        for (i in src.indices) {
            val r = Color.red(src[i]); val g = Color.green(src[i]); val bl = Color.blue(src[i])
            val br = Color.red(blurred[i]); val bg = Color.green(blurred[i]); val bb = Color.blue(blurred[i])
            val outR = (r + (r - br) * strength).roundToInt().coerceIn(0, 255)
            val outG = (g + (g - bg) * strength).roundToInt().coerceIn(0, 255)
            val outB = (bl + (bl - bb) * strength).roundToInt().coerceIn(0, 255)
            blurred[i] = Color.rgb(outR, outG, outB)
        }

        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        out.setPixels(blurred, 0, w, 0, 0, w, h)
        return out
    }
}
