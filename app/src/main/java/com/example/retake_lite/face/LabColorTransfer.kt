package com.example.retake_lite.face

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

object LabColorTransfer {

    fun transfer(source: Bitmap, target: Bitmap, mask: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        if (w != target.width || h != target.height || w != mask.width || h != mask.height) {
            return source
        }

        val srcPx = IntArray(w * h)
        val tgtPx = IntArray(w * h)
        val mskPx = IntArray(w * h)
        source.getPixels(srcPx, 0, w, 0, 0, w, h)
        target.getPixels(tgtPx, 0, w, 0, 0, w, h)
        mask.getPixels(mskPx, 0, w, 0, 0, w, h)

        var srcL = 0.0; var srcA = 0.0; var srcB = 0.0; var srcN = 0
        var tgtL = 0.0; var tgtA = 0.0; var tgtB = 0.0; var tgtN = 0

        for (i in srcPx.indices) {
            if (Color.alpha(mskPx[i]) < 64) continue
            if (Color.alpha(srcPx[i]) < 32) continue
            val lab = rgbToLab(srcPx[i])
            srcL += lab[0]; srcA += lab[1]; srcB += lab[2]
            srcN++
        }

        for (i in tgtPx.indices) {
            if (Color.alpha(mskPx[i]) < 64) continue
            val lab = rgbToLab(tgtPx[i])
            tgtL += lab[0]; tgtA += lab[1]; tgtB += lab[2]
            tgtN++
        }

        if (srcN == 0 || tgtN == 0) return source

        srcL /= srcN; srcA /= srcN; srcB /= srcN
        tgtL /= tgtN; tgtA /= tgtN; tgtB /= tgtN

        var srcVarL = 0.0; var srcVarA = 0.0; var srcVarB = 0.0
        var tgtVarL = 0.0; var tgtVarA = 0.0; var tgtVarB = 0.0
        for (i in srcPx.indices) {
            if (Color.alpha(mskPx[i]) < 64) continue
            if (Color.alpha(srcPx[i]) >= 32) {
                val lab = rgbToLab(srcPx[i])
                srcVarL += (lab[0] - srcL).pow(2)
                srcVarA += (lab[1] - srcA).pow(2)
                srcVarB += (lab[2] - srcB).pow(2)
            }
            val tLab = rgbToLab(tgtPx[i])
            tgtVarL += (tLab[0] - tgtL).pow(2)
            tgtVarA += (tLab[1] - tgtA).pow(2)
            tgtVarB += (tLab[2] - tgtB).pow(2)
        }
        val stdSrcL = max(sqrt(srcVarL / srcN), 1.0)
        val stdSrcA = max(sqrt(srcVarA / srcN), 1.0)
        val stdSrcB = max(sqrt(srcVarB / srcN), 1.0)
        val stdTgtL = max(sqrt(tgtVarL / tgtN), 1.0)
        val stdTgtA = max(sqrt(tgtVarA / tgtN), 1.0)
        val stdTgtB = max(sqrt(tgtVarB / tgtN), 1.0)

        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val outPx = IntArray(w * h)
        source.getPixels(outPx, 0, w, 0, 0, w, h)

        for (i in outPx.indices) {
            if (Color.alpha(mskPx[i]) < 64 || Color.alpha(outPx[i]) < 32) continue
            val lab = rgbToLab(outPx[i])
            val newL = ((lab[0] - srcL) * (stdTgtL / stdSrcL) + tgtL).coerceIn(0.0, 100.0)
            val newA = ((lab[1] - srcA) * (stdTgtA / stdSrcA) + tgtA).coerceIn(-128.0, 127.0)
            val newB = ((lab[2] - srcB) * (stdTgtB / stdSrcB) + tgtB).coerceIn(-128.0, 127.0)
            outPx[i] = labToRgb(newL, newA, newB, Color.alpha(outPx[i]))
        }
        out.setPixels(outPx, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * Corrección MANUAL adicional, aplicada después de la automática.
     * Permite que el usuario contrarreste un tinte verde/azul residual o
     * ajuste el brillo de la cara con sliders, sin recalcular el match
     * estadístico de transfer().
     *
     * @param mask limita el área afectada (la misma máscara de la cara).
     */
    fun applyManualShift(
        bitmap: Bitmap,
        mask: Bitmap,
        lightnessShift: Float,
        redGreenShift: Float,
        blueYellowShift: Float
    ): Bitmap {
        if (lightnessShift == 0f && redGreenShift == 0f && blueYellowShift == 0f) return bitmap

        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        val mskPx = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        mask.getPixels(mskPx, 0, w, 0, 0, w, h)

        for (i in px.indices) {
            val m = Color.alpha(mskPx[i])
            if (m < 8) continue
            val alpha = Color.alpha(px[i])
            if (alpha < 8) continue

            val lab = rgbToLab(px[i])
            // El shift se aplica proporcional a la fuerza de la máscara, para
            // que el efecto se desvanezca suavemente cerca del borde difuminado.
            val strength = m / 255f
            val newL = (lab[0] + lightnessShift * strength).coerceIn(0.0, 100.0)
            val newA = (lab[1] + redGreenShift * strength).coerceIn(-128.0, 127.0)
            val newB = (lab[2] + blueYellowShift * strength).coerceIn(-128.0, 127.0)
            px[i] = labToRgb(newL, newA, newB, alpha)
        }

        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun rgbToLab(color: Int): DoubleArray {
        var r = Color.red(color) / 255.0
        var g = Color.green(color) / 255.0
        var b = Color.blue(color) / 255.0
        r = if (r > 0.04045) ((r + 0.055) / 1.055).pow(2.4) else r / 12.92
        g = if (g > 0.04045) ((g + 0.055) / 1.055).pow(2.4) else g / 12.92
        b = if (b > 0.04045) ((b + 0.055) / 1.055).pow(2.4) else b / 12.92

        val x = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047
        val y = (r * 0.2126 + g * 0.7152 + b * 0.0722) / 1.00000
        val z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883

        val fx = if (x > 0.008856) x.pow(1.0 / 3.0) else (7.787 * x) + (16.0 / 116.0)
        val fy = if (y > 0.008856) y.pow(1.0 / 3.0) else (7.787 * y) + (16.0 / 116.0)
        val fz = if (z > 0.008856) z.pow(1.0 / 3.0) else (7.787 * z) + (16.0 / 116.0)

        return doubleArrayOf((116.0 * fy) - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    private fun labToRgb(l: Double, a: Double, b: Double, alpha: Int): Int {
        var y = (l + 16.0) / 116.0
        var x = a / 500.0 + y
        var z = y - b / 200.0

        val x3 = x * x * x
        val y3 = y * y * y
        val z3 = z * z * z
        x = if (x3 > 0.008856) x3 else (x - 16.0 / 116.0) / 7.787
        y = if (y3 > 0.008856) y3 else (y - 16.0 / 116.0) / 7.787
        z = if (z3 > 0.008856) z3 else (z - 16.0 / 116.0) / 7.787

        var r = x * 0.95047
        var g = y * 1.00000
        var bl = z * 1.08883
        r = if (r > 0.0031308) 1.055 * r.pow(1.0 / 2.4) - 0.055 else 12.92 * r
        g = if (g > 0.0031308) 1.055 * g.pow(1.0 / 2.4) - 0.055 else 12.92 * g
        bl = if (bl > 0.0031308) 1.055 * bl.pow(1.0 / 2.4) - 0.055 else 12.92 * bl

        return Color.argb(
            alpha,
            (r * 255).toInt().coerceIn(0, 255),
            (g * 255).toInt().coerceIn(0, 255),
            (bl * 255).toInt().coerceIn(0, 255)
        )
    }
}