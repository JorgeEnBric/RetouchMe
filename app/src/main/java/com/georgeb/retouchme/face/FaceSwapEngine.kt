package com.georgeb.retouchme.face

import android.content.Context
import android.graphics.Bitmap
import com.georgeb.retouchme.data.FaceImageEntity
import com.google.mlkit.vision.face.Face

data class FaceSwapAssignment(
    val faceIndex: Int,
    val profileId: Long,
    val referenceImageId: Long? = null
)

/**
 * Fachada del motor de intercambio. Internamente usa el FaceRetakeEngine de
 * dos fases (automático + ajustes manuales), pero expone una función simple
 * de "swap directo" para el caso de uso más común: aplicar el resultado
 * automático sin ajustes, a una o varias caras de la imagen.
 *
 * Si tu UI necesita el flujo con sliders (ajustar zoom/halo/tono después del
 * swap automático), usa retakeEngine directamente con computeAutoResult()
 * y render() en vez de esta función de conveniencia.
 */
class FaceSwapEngine(context: Context, private val faceDetector: FaceDetectorHelper) {

    private val embedder = FaceEmbedder(context)
    val retakeEngine = FaceRetakeEngine(faceDetector, embedder)

    /** Swap automático, sin ajustes manuales (FaceAdjustments por defecto). */
    suspend fun swapFaces(
        targetBitmap: Bitmap,
        targetFaces: List<Face>,
        assignments: List<FaceSwapAssignment>,
        profileImages: Map<Long, List<FaceImageEntity>>
    ): Bitmap {
        var result = targetBitmap.copy(Bitmap.Config.ARGB_8888, true)

        for (assignment in assignments) {
            val targetFace = targetFaces.getOrNull(assignment.faceIndex) ?: continue
            val images = profileImages[assignment.profileId] ?: continue
            if (images.isEmpty()) continue

            val auto = retakeEngine.computeAutoResult(
                result, targetFace, assignment.profileId, images, assignment.referenceImageId
            ) ?: continue

            result = retakeEngine.render(auto, FaceAdjustments())
            retakeEngine.releaseAutoResult(auto)
        }
        return result
    }

    fun close() {
        retakeEngine.close()
    }

    /**
     * Consulta cuál foto del perfil elegiría el modo "automático" (vía
     * OpenFace), SIN correr ningún swap. Pensado para resaltar esa foto en
     * tu selector manual, o para resolver el referenceImageId real cuando
     * el usuario elige el modo automático en el toggle de la UI.
     *
     * Reutiliza el mismo modelCache interno de FaceRetakeEngine, así que si
     * ya se calculó antes para este profileId, es prácticamente instantáneo.
     */
    suspend fun getAutoSelectedReferenceId(
        profileId: Long,
        images: List<FaceImageEntity>
    ): Long? = retakeEngine.getBestReferenceId(profileId, images)
}