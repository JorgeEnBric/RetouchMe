package com.example.retake_lite.face

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import com.example.retake_lite.data.FaceImageEntity
import com.google.mlkit.vision.face.Face
import kotlin.math.PI
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * Resultado intermedio de la fase AUTOMÁTICA: todo lo que se necesita para
 * re-renderizar con distintos FaceAdjustments sin volver a detectar caras
 * ni decodificar el bitmap de referencia. Esto es lo que hace que mover un
 * slider en la UI sea instantáneo en vez de recorrer todo el pipeline.
 */
data class AutoRetakeResult(
    val targetBitmap: Bitmap,
    val targetFace: Face,
    val sourceBitmap: Bitmap,
    val sourceFace: Face,
    val baseMatrix: Matrix
)

class FaceRetakeEngine(
    private val faceDetector: FaceDetectorHelper,
    private val embedder: FaceEmbedder
) {

    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val modelCache = mutableMapOf<Long, FaceProfileModel?>()

    /** Fase 1: detección + elección de referencia + matriz base. Resultado cacheable por cara. */
    suspend fun computeAutoResult(
        targetBitmap: Bitmap,
        targetFace: Face,
        profileId: Long,
        profileImages: List<FaceImageEntity>,
        preferredReferenceId: Long?
    ): AutoRetakeResult? {
        val model = modelCache.getOrPut(profileId) {
            ProfileFaceModelBuilder.build(profileImages, faceDetector, embedder)
        }
        val reference = resolveReference(profileImages, preferredReferenceId, model) ?: return null
        val sourceBitmap = BitmapFactory.decodeFile(reference.imagePath) ?: return null

        val sourceFace = faceDetector.detectRawFaces(sourceBitmap)
            .maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        if (sourceFace == null) {
            sourceBitmap.recycle()
            return null
        }

        val dstSix = FaceLandmarkUtils.sixPointsWithForehead(targetFace)
        val srcSix = FaceLandmarkUtils.sixPointsWithForehead(sourceFace)
        val (dstPoints, srcPoints) = if (dstSix != null && srcSix != null) {
            dstSix to srcSix
        } else {
            val dstFive = FaceLandmarkUtils.fivePoints(targetFace)
            val srcFive = FaceLandmarkUtils.fivePoints(sourceFace)
            if (dstFive == null || srcFive == null) {
                sourceBitmap.recycle()
                return null
            }
            dstFive to srcFive
        }

        val baseMatrix = SimilarityTransform.estimate(srcPoints, dstPoints)
        if (baseMatrix == null) {
            sourceBitmap.recycle()
            return null
        }

        return AutoRetakeResult(targetBitmap, targetFace, sourceBitmap, sourceFace, baseMatrix)
    }

    /**
     * Fase 2: renderiza con ajustes manuales. Se puede llamar muchas veces
     * (cada vez que el usuario mueve un slider) reutilizando el mismo
     * AutoRetakeResult — no vuelve a detectar ni a decodificar nada.
     */
    fun render(auto: AutoRetakeResult, adjustments: FaceAdjustments): Bitmap {
        val box = auto.targetFace.boundingBox
        val cx = box.centerX().toFloat()
        val cy = box.centerY().toFloat()
        val faceSize = kotlin.math.max(box.width(), box.height()).toFloat()

        val matrix = Matrix(auto.baseMatrix)
        matrix.postScale(adjustments.scale, adjustments.scale, cx, cy)
        matrix.postRotate(adjustments.rotationDegrees, cx, cy)
        matrix.postTranslate(
            adjustments.offsetXRatio * faceSize,
            adjustments.offsetYRatio * faceSize
        )

        val w = auto.targetBitmap.width
        val h = auto.targetBitmap.height
        val result = auto.targetBitmap.copy(Bitmap.Config.ARGB_8888, true)

        val overlay = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(overlay).drawBitmap(auto.sourceBitmap, matrix, drawPaint)

        val mask = FaceMaskBuilder.createFaceMask(
            w, h, auto.targetFace,
            adjustments.edgeShrink,
            adjustments.edgeShrinkLeft,
            adjustments.edgeShrinkRight,
            adjustments.edgeShrinkTop,
            adjustments.edgeShrinkBottom
        )

        val clipped = LaplacianBlender.clipOverlayToMask(overlay, mask)
        overlay.recycle()

        val smoothed = if (adjustments.smoothing > 0f) {
            applySkinSmoothing(clipped, mask, adjustments.smoothing)
        } else clipped
        if (smoothed !== clipped) clipped.recycle()

        val finalBmp = LaplacianBlender.blend(result, smoothed, mask, 9f)
        if (finalBmp !== smoothed) smoothed.recycle()
        mask.recycle()

        return finalBmp
    }

    fun releaseAutoResult(auto: AutoRetakeResult) {
        auto.sourceBitmap.recycle()
    }

    /**
     * Devuelve el id de la foto que el modo automático elegiría para este
     * perfil, sin correr ningún swap. Usa/llena el mismo modelCache que
     * computeAutoResult, así que no duplica trabajo si ya se calculó antes.
     */
    suspend fun getBestReferenceId(profileId: Long, images: List<FaceImageEntity>): Long? {
        if (images.isEmpty()) return null
        val model = modelCache.getOrPut(profileId) {
            ProfileFaceModelBuilder.build(images, faceDetector, embedder)
        }
        return model?.bestReferenceId ?: images.first().id
    }

    private fun resolveReference(
        images: List<FaceImageEntity>,
        preferredId: Long?,
        model: FaceProfileModel?
    ): FaceImageEntity? {
        if (images.isEmpty()) return null
        preferredId?.let { id -> images.firstOrNull { it.id == id }?.let { return it } }
        model?.let { m -> images.firstOrNull { it.id == m.bestReferenceId }?.let { return it } }
        return images.first()
    }

    /**
     * Suavizado de piel (efecto porcelana/embellecido).
     * Aplica un desenfoque gaussiano sobre la zona de la máscara y lo fusiona
     * según la fuerza indicada por [strength] (0..1).
     */
    private fun applySkinSmoothing(bitmap: Bitmap, mask: Bitmap, strength: Float): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val radius = (strength * 20f + 1f).toInt()

        val srcMat = Mat()
        Utils.bitmapToMat(bitmap, srcMat)

        val blurred = Mat()
        Imgproc.GaussianBlur(srcMat, blurred, org.opencv.core.Size(radius.toDouble() * 2 + 1, radius.toDouble() * 2 + 1), 0.0)

        val maskPx = IntArray(w * h)
        mask.getPixels(maskPx, 0, w, 0, 0, w, h)

        val srcPx = IntArray(w * h)
        bitmap.getPixels(srcPx, 0, w, 0, 0, w, h)

        val blurPx = IntArray(w * h)
        val blurBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(blurred, blurBmp)
        blurBmp.getPixels(blurPx, 0, w, 0, 0, w, h)
        blurBmp.recycle()
        srcMat.release()
        blurred.release()

        val out = IntArray(w * h)
        for (i in out.indices) {
            val m = Color.alpha(maskPx[i]) / 255f * strength
            if (m <= 0f) {
                out[i] = srcPx[i]
            } else {
                val sw = m.coerceIn(0f, 1f)
                out[i] = Color.argb(
                    Color.alpha(srcPx[i]),
                    lerp(Color.red(srcPx[i]), Color.red(blurPx[i]), sw),
                    lerp(Color.green(srcPx[i]), Color.green(blurPx[i]), sw),
                    lerp(Color.blue(srcPx[i]), Color.blue(blurPx[i]), sw)
                )
            }
        }

        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    private fun lerp(a: Int, b: Int, t: Float): Int =
        (a + (b - a) * t).toInt().coerceIn(0, 255)

    fun close() {
        embedder.close()
    }
}
