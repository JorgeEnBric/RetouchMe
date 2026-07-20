package com.example.retake_lite.face

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.pow

object LabColorTransfer {

    fun transfer(source: Bitmap, target: Bitmap, mask: Bitmap): Bitmap {
        return source
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