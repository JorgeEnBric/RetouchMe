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

        return AutoRetakeResult(targetBitmap, targetFace, sourceBitmap, baseMatrix)
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

        // Matriz final = automática + ajustes manuales (escala, rotación,
        // desplazamiento), todo pivotando sobre el centro de la cara destino.
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
        Canvas(overlay).drawColor(Color.TRANSPARENT)
        Canvas(overlay).drawBitmap(auto.sourceBitmap, matrix, drawPaint)

        // edgeShrink corrige el halo: contrae el óvalo de la máscara hacia el
        // centro antes de difuminar, "comiéndose" el borde problemático.
        val mask = FaceMaskBuilder.createFaceMask(w, h, auto.targetFace, adjustments.edgeShrink)

        val clipped = LaplacianBlender.clipOverlayToMask(overlay, mask)
        if (clipped !== overlay) overlay.recycle()

        val colorMatched = LabColorTransfer.transfer(clipped, result, mask)
        if (colorMatched !== clipped) clipped.recycle()

        val manuallyShifted = LabColorTransfer.applyManualShift(
            colorMatched, mask,
            adjustments.lightnessShift,
            adjustments.redGreenShift,
            adjustments.blueYellowShift
        )
        if (manuallyShifted !== colorMatched) colorMatched.recycle()

        val blended = LaplacianBlender.blend(result, manuallyShifted, mask, adjustments.featherRadiusPx)
        manuallyShifted.recycle()
        mask.recycle()

        return blended
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

    fun close() {
        embedder.close()
    }
}