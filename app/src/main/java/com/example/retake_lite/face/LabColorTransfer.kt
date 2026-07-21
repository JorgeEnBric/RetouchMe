package com.example.retake_lite.face

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.pow
import kotlin.math.sqrt
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

object LabColorTransfer {

    /**
     * Corrección de crominancia (canales A y B en LAB).
     * Ajusta solo el balance de color del rostro fuente para coincidir con
     * el destino, pero PRESERVA la luminancia (canal L) del rostro fuente
     * para no destruir su iluminación original.
     */
    fun statisticalTransfer(overlay: Bitmap, target: Bitmap, mask: Bitmap): Bitmap {
        val w = overlay.width
        val h = overlay.height
        val ovPx = IntArray(w * h)
        val tgPx = IntArray(w * h)
        val mskPx = IntArray(w * h)
        overlay.getPixels(ovPx, 0, w, 0, 0, w, h)
        target.getPixels(tgPx, 0, w, 0, 0, w, h)
        mask.getPixels(mskPx, 0, w, 0, 0, w, h)

        val MASK_THRESH = 128
        var count = 0L
        var sumSrcA = 0.0; var sumSrcB = 0.0
        var sumDstA = 0.0; var sumDstB = 0.0

        // Media de crominancia (A, B) del overlay y del destino
        for (i in ovPx.indices) {
            if (Color.alpha(mskPx[i]) < MASK_THRESH) continue
            val labSrc = rgbToLab(ovPx[i])
            val labDst = rgbToLab(tgPx[i])
            sumSrcA += labSrc[1]; sumSrcB += labSrc[2]
            sumDstA += labDst[1]; sumDstB += labDst[2]
            count++
        }
        if (count == 0L) return overlay

        val meanSrcA = (sumSrcA / count).toFloat()
        val meanSrcB = (sumSrcB / count).toFloat()
        val meanDstA = (sumDstA / count).toFloat()
        val meanDstB = (sumDstB / count).toFloat()

        // Delta de crominancia: lo que hay que sumar al fuente para igualar al destino
        val deltaA = meanDstA - meanSrcA
        val deltaB = meanDstB - meanSrcB
        if (deltaA == 0f && deltaB == 0f) return overlay

        // Aplicar corrección píxel a píxel solo donde la máscara tenga peso
        val out = IntArray(w * h)
        for (i in ovPx.indices) {
            val m = Color.alpha(mskPx[i])
            if (m < 8) {
                out[i] = ovPx[i]
                continue
            }
            val lab = rgbToLab(ovPx[i])
            val strength = (m / 255f).toFloat()

            val newA = (lab[1] + deltaA * strength).coerceIn(-128.0, 127.0)
            val newB = (lab[2] + deltaB * strength).coerceIn(-128.0, 127.0)
            // L se conserva tal cual del overlay (luminancia fuente)
            out[i] = labToRgb(lab[0], newA, newB, Color.alpha(ovPx[i]))
        }

        val result = overlay.copy(Bitmap.Config.ARGB_8888, true)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * Variante de [statisticalTransfer] pensada para el flujo de "Retake":
     * la foto destino puede tener, justo en el área de la máscara, el
     * mismo rostro con el problema de tono que el usuario quiere corregir
     * (por eso lo está reemplazando). Usar ESE rostro como referencia de
     * color -como hace statisticalTransfer()- hace que el rostro nuevo
     * (bueno) se ajuste para igualar el tono malo del rostro viejo,
     * anulando la corrección.
     *
     * En vez de eso, esta función mide el color de referencia en un
     * ANILLO de piel real alrededor de la máscara (frente, mejillas,
     * mandíbula, cuello) — zona visible en la foto destino que refleja
     * la iluminación real de esa foto sin heredar el defecto puntual
     * del rostro que se va a tapar.
     *
     * @param faceMask máscara de la cara (donde se pinta el resultado).
     * @param ringWidthPx grosor del anillo de muestreo alrededor de la máscara.
     */
    fun statisticalTransferToSurroundingSkin(
        overlay: Bitmap,
        target: Bitmap,
        faceMask: Bitmap,
        ringWidthPx: Int
    ): Bitmap {
        val ring = buildRingMask(faceMask, ringWidthPx)
        try {
            return statisticalTransferWithMasks(overlay, target, sourceMask = faceMask, referenceMask = ring)
        } finally {
            ring.recycle()
        }
    }

    /**
     * Núcleo compartido: mide la crominancia media del ORIGEN dentro de
     * [sourceMask] y la del DESTINO dentro de [referenceMask] (pueden ser
     * máscaras distintas), y desplaza el origen para igualar esa media,
     * preservando su luminancia. La corrección se pinta únicamente donde
     * [sourceMask] tiene peso.
     */
    private fun statisticalTransferWithMasks(
        overlay: Bitmap,
        target: Bitmap,
        sourceMask: Bitmap,
        referenceMask: Bitmap
    ): Bitmap {
        val w = overlay.width
        val h = overlay.height
        val ovPx = IntArray(w * h)
        val tgPx = IntArray(w * h)
        val srcMaskPx = IntArray(w * h)
        val refMaskPx = IntArray(w * h)
        overlay.getPixels(ovPx, 0, w, 0, 0, w, h)
        target.getPixels(tgPx, 0, w, 0, 0, w, h)
        sourceMask.getPixels(srcMaskPx, 0, w, 0, 0, w, h)
        referenceMask.getPixels(refMaskPx, 0, w, 0, 0, w, h)

        val MASK_THRESH = 128

        var srcCount = 0L
        var sumSrcA = 0.0; var sumSrcB = 0.0
        for (i in ovPx.indices) {
            if (Color.alpha(srcMaskPx[i]) < MASK_THRESH) continue
            val lab = rgbToLab(ovPx[i])
            sumSrcA += lab[1]; sumSrcB += lab[2]
            srcCount++
        }
        if (srcCount == 0L) return overlay

        var refCount = 0L
        var sumRefA = 0.0; var sumRefB = 0.0
        for (i in tgPx.indices) {
            if (Color.alpha(refMaskPx[i]) < MASK_THRESH) continue
            val lab = rgbToLab(tgPx[i])
            sumRefA += lab[1]; sumRefB += lab[2]
            refCount++
        }
        // Si no hay suficiente piel de referencia visible (p. ej. cara pegada
        // al borde de la imagen), no forzamos ningún cambio de color.
        if (refCount == 0L) return overlay

        val meanSrcA = (sumSrcA / srcCount).toFloat()
        val meanSrcB = (sumSrcB / srcCount).toFloat()
        val meanRefA = (sumRefA / refCount).toFloat()
        val meanRefB = (sumRefB / refCount).toFloat()

        val deltaA = meanRefA - meanSrcA
        val deltaB = meanRefB - meanSrcB
        if (deltaA == 0f && deltaB == 0f) return overlay

        val out = IntArray(w * h)
        for (i in ovPx.indices) {
            val m = Color.alpha(srcMaskPx[i])
            if (m < 8) {
                out[i] = ovPx[i]
                continue
            }
            val lab = rgbToLab(ovPx[i])
            val strength = m / 255f
            val newA = (lab[1] + deltaA * strength).coerceIn(-128.0, 127.0)
            val newB = (lab[2] + deltaB * strength).coerceIn(-128.0, 127.0)
            out[i] = labToRgb(lab[0], newA, newB, Color.alpha(ovPx[i]))
        }

        val result = overlay.copy(Bitmap.Config.ARGB_8888, true)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * Construye una máscara "anillo": dilata [faceMask] por [ringWidthPx] y
     * le resta la máscara original, dejando solo el borde exterior. Esa
     * franja cae sobre piel real (o pelo/fondo en los extremos, que se
     * promedian pero pesan poco frente a la piel dominante del anillo).
     */
    private fun buildRingMask(faceMask: Bitmap, ringWidthPx: Int): Bitmap {
        val w = faceMask.width
        val h = faceMask.height

        val maskRgba = Mat()
        Utils.bitmapToMat(faceMask, maskRgba)

        val alphaChannel = Mat()
        Core.extractChannel(maskRgba, alphaChannel, 3)

        val kernelSize = (ringWidthPx * 2 + 1).coerceAtLeast(3)
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_ELLIPSE,
            org.opencv.core.Size(kernelSize.toDouble(), kernelSize.toDouble())
        )
        val dilated = Mat()
        Imgproc.dilate(alphaChannel, dilated, kernel)

        val ringAlpha = Mat()
        Core.subtract(dilated, alphaChannel, ringAlpha)

        val ringBytes = ByteArray(w * h)
        ringAlpha.get(0, 0, ringBytes)

        val ringPx = IntArray(w * h)
        for (i in ringPx.indices) {
            val a = ringBytes[i].toInt() and 0xFF
            ringPx[i] = Color.argb(a, 255, 255, 255)
        }

        val ringBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        ringBitmap.setPixels(ringPx, 0, w, 0, 0, w, h)

        maskRgba.release(); alphaChannel.release()
        kernel.release(); dilated.release(); ringAlpha.release()

        return ringBitmap
    }

    /**
     * Post-proceso de tono/iluminación: fuerza que la región incrustada en
     * [compositeOverlay] (ya deformada y pegada, dentro de [faceMask]) tenga
     * EXACTAMENTE las mismas estadísticas de color — media Y desviación
     * estándar de L, A y B — que la foto de referencia original elegida por
     * el usuario ([referenceBitmap], dentro de su propio [referenceMask]).
     *
     * A diferencia de statisticalTransfer/statisticalTransferToSurroundingSkin,
     * esta función NO usa la foto destino como objetivo en absoluto — usa
     * únicamente la imagen de referencia como fuente de verdad del tono e
     * iluminación, así que no puede fallar por elegir mal el objetivo. Al
     * igualar también la desviación estándar (no solo la media) reproduce
     * el contraste de luces/sombras original de la referencia, no solo su
     * color promedio — esto es lo que hace que la iluminación (no solo el
     * tono) también se preserve.
     *
     * @param compositeOverlay bitmap con el rostro ya deformado/pegado (mismo
     *   tamaño que la imagen destino), el que se está construyendo en render().
     * @param faceMask máscara de la cara en el destino (mismo tamaño que compositeOverlay).
     * @param referenceBitmap la foto de referencia ORIGINAL sin deformar (auto.sourceBitmap).
     * @param referenceMask máscara de la cara dentro de referenceBitmap (su propio tamaño).
     */
    fun matchReferenceTone(
        compositeOverlay: Bitmap,
        faceMask: Bitmap,
        referenceBitmap: Bitmap,
        referenceMask: Bitmap
    ): Bitmap {
        val refStats = labStats(referenceBitmap, referenceMask) ?: return compositeOverlay
        val curStats = labStats(compositeOverlay, faceMask) ?: return compositeOverlay

        val w = compositeOverlay.width
        val h = compositeOverlay.height
        val px = IntArray(w * h)
        val maskPx = IntArray(w * h)
        compositeOverlay.getPixels(px, 0, w, 0, 0, w, h)
        faceMask.getPixels(maskPx, 0, w, 0, 0, w, h)

        for (i in px.indices) {
            val m = Color.alpha(maskPx[i])
            if (m < 8) continue
            val a = Color.alpha(px[i])
            if (a < 8) continue

            val lab = rgbToLab(px[i])
            val strength = m / 255f

            val targetL = normalize(lab[0], curStats.meanL, curStats.stdL, refStats.meanL, refStats.stdL)
            val targetA = normalize(lab[1], curStats.meanA, curStats.stdA, refStats.meanA, refStats.stdA)
            val targetB = normalize(lab[2], curStats.meanB, curStats.stdB, refStats.meanB, refStats.stdB)

            // Se atenúa con la fuerza de la máscara para que el efecto se
            // desvanezca suavemente hacia el borde difuminado.
            val newL = lerpD(lab[0], targetL, strength).coerceIn(0.0, 100.0)
            val newA = lerpD(lab[1], targetA, strength).coerceIn(-128.0, 127.0)
            val newB = lerpD(lab[2], targetB, strength).coerceIn(-128.0, 127.0)

            px[i] = labToRgb(newL, newA, newB, a)
        }

        val out = compositeOverlay.copy(Bitmap.Config.ARGB_8888, true)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private data class LabStats(
        val meanL: Double, val stdL: Double,
        val meanA: Double, val stdA: Double,
        val meanB: Double, val stdB: Double
    )

    private fun labStats(bitmap: Bitmap, mask: Bitmap): LabStats? {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        val maskPx = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        mask.getPixels(maskPx, 0, w, 0, 0, w, h)

        val labs = ArrayList<DoubleArray>()
        var sumL = 0.0; var sumA = 0.0; var sumB = 0.0
        for (i in px.indices) {
            if (Color.alpha(maskPx[i]) < 128) continue
            val lab = rgbToLab(px[i])
            labs.add(lab)
            sumL += lab[0]; sumA += lab[1]; sumB += lab[2]
        }
        val n = labs.size
        if (n == 0) return null

        val meanL = sumL / n
        val meanA = sumA / n
        val meanB = sumB / n

        var varL = 0.0; var varA = 0.0; var varB = 0.0
        for (lab in labs) {
            varL += (lab[0] - meanL).pow(2)
            varA += (lab[1] - meanA).pow(2)
            varB += (lab[2] - meanB).pow(2)
        }
        varL /= n; varA /= n; varB /= n

        return LabStats(meanL, sqrt(varL), meanA, sqrt(varA), meanB, sqrt(varB))
    }

    /** Reescala `value` de la distribución (curMean, curStd) a (refMean, refStd), estilo Reinhard. */
    private fun normalize(value: Double, curMean: Double, curStd: Double, refMean: Double, refStd: Double): Double {
        if (curStd < 1e-3) return value
        val z = (value - curMean) / curStd
        return refMean + z * refStd
    }

    private fun lerpD(a: Double, b: Double, t: Float): Double = a + (b - a) * t

    /**
     * Corrección MANUAL adicional, aplicada después de la automática.
     * Permite que el usuario contrarreste un tinte verde/azul residual o
     * ajuste el brillo de la cara con sliders, sin recalcular el match
     * estadístico de transfer().
     *
     * @param mask limita el área afectada (la misma máscara de la cara).
     */
    fun matchRgb(overlay: Bitmap, target: Bitmap, mask: Bitmap) {
        val w = overlay.width
        val h = overlay.height
        val ovPx = IntArray(w * h)
        val tgPx = IntArray(w * h)
        val mskPx = IntArray(w * h)
        overlay.getPixels(ovPx, 0, w, 0, 0, w, h)
        target.getPixels(tgPx, 0, w, 0, 0, w, h)
        mask.getPixels(mskPx, 0, w, 0, 0, w, h)

        var count = 0L
        var sR = 0L; var sG = 0L; var sB = 0L
        var dR = 0L; var dG = 0L; var dB = 0L

        for (i in ovPx.indices) {
            val m = Color.alpha(mskPx[i])
            if (m < 128) continue
            val a = Color.alpha(ovPx[i])
            if (a < 8) continue
            sR += Color.red(ovPx[i]); sG += Color.green(ovPx[i]); sB += Color.blue(ovPx[i])
            dR += Color.red(tgPx[i]); dG += Color.green(tgPx[i]); dB += Color.blue(tgPx[i])
            count++
        }
        if (count == 0L) return

        val offR = (dR / count - sR / count).toInt()
        val offG = (dG / count - sG / count).toInt()
        val offB = (dB / count - sB / count).toInt()
        if (offR == 0 && offG == 0 && offB == 0) return

        for (i in ovPx.indices) {
            val m = Color.alpha(mskPx[i])
            if (m < 8) continue
            val a = Color.alpha(ovPx[i])
            if (a < 8) continue
            val f = m / 255f
            val nr = (Color.red(ovPx[i]) + offR * f).toInt().coerceIn(0, 255)
            val ng = (Color.green(ovPx[i]) + offG * f).toInt().coerceIn(0, 255)
            val nb = (Color.blue(ovPx[i]) + offB * f).toInt().coerceIn(0, 255)
            ovPx[i] = Color.argb(a, nr, ng, nb)
        }
        overlay.setPixels(ovPx, 0, w, 0, 0, w, h)
    }

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