package com.georgeb.retouchme.face

import android.graphics.BitmapFactory
import com.georgeb.retouchme.data.FaceImageEntity

/**
 * Modelo promediado de un perfil, basado en embeddings de OpenFace.
 * Se usa ÚNICAMENTE para elegir cuál foto del perfil es la más
 * "representativa" geométrica/perceptualmente — el resultado final del
 * swap sigue saliendo del pipeline de blending de píxeles reales
 * (FaceRetakeEngine), no de un generador.
 */
data class FaceProfileModel(
    /** Embedding promedio de 128-d (OpenFace), normalizado. */
    val embedding: FloatArray,
    val sampleCount: Int,
    /** Foto individual más cercana al embedding promedio — la que se usa como referencia real. */
    val bestReferenceId: Long
)

object ProfileFaceModelBuilder {

    private data class Sample(val image: FaceImageEntity, val embedding: FloatArray)

    suspend fun build(
        images: List<FaceImageEntity>,
        faceDetector: FaceDetectorHelper,
        embedder: FaceEmbedder
    ): FaceProfileModel? {
        if (images.isEmpty()) return null

        val samples = mutableListOf<Sample>()
        for (image in images) {
            val bitmap = BitmapFactory.decodeFile(image.imagePath) ?: continue
            try {
                val face = faceDetector.detectRawFaces(bitmap)
                    .maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                    ?: continue
                val aligned = FaceAligner.alignForOpenFace(bitmap, face) ?: continue
                try {
                    val embedding = embedder.embed(aligned.bitmap)
                    samples.add(Sample(image, embedding))
                } finally {
                    aligned.bitmap.recycle()
                }
            } finally {
                bitmap.recycle()
            }
        }

        if (samples.isEmpty()) return null

        val avgEmbedding = embedder.averageEmbeddings(samples.map { it.embedding })
        val best = samples.minBy { cosineDistance(it.embedding, avgEmbedding) }

        return FaceProfileModel(
            embedding = avgEmbedding,
            sampleCount = samples.size,
            bestReferenceId = best.image.id
        )
    }

    private fun cosineDistance(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return 1f - dot // ambos vectores normalizados (norma 1)
    }
}