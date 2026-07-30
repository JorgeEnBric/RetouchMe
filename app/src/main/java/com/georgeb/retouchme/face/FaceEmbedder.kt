package com.georgeb.retouchme.face

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Envoltura de OpenFace (nn4.small2.v1, Carnegie Mellon University,
 * licencia Apache 2.0 — libre para uso comercial). Convierte una cara
 * alineada a 96x96 en un embedding de 128 floats que representa la
 * identidad de la persona, robusto a pose/luz/expresión.
 *
 * Se usa SOLO para elegir la mejor foto de referencia dentro del perfil
 * (ver ProfileFaceModelBuilder) — el resultado final sigue generándose
 * con el pipeline de geometría + blending (SimilarityTransform,
 * FaceMaskBuilder, LaplacianBlender, LabColorTransfer), no con un
 * generador de IA.
 */
class FaceEmbedder(context: Context, modelAssetName: String = "openface.onnx") {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val modelBytes = context.assets.open(modelAssetName).readBytes()
        session = env.createSession(modelBytes, OrtSession.SessionOptions())
    }

    /** @param alignedFace96 bitmap de 96x96 ya alineado con FaceAligner.alignForOpenFace */
    fun embed(alignedFace96: Bitmap): FloatArray {
        require(alignedFace96.width == 96 && alignedFace96.height == 96) {
            "OpenFace requiere input 96x96 ya alineado"
        }

        val inputName = session.inputNames.iterator().next()
        val inputBuffer = bitmapToCHWTensor(alignedFace96)

        OnnxTensor.createTensor(env, inputBuffer, longArrayOf(1, 3, 96, 96)).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val output = result[0].value as Array<FloatArray>
                return normalize(output[0])
            }
        }
    }

    /** Promedia varios embeddings (de distintas fotos del perfil) en uno solo, re-normalizado. */
    fun averageEmbeddings(embeddings: List<FloatArray>): FloatArray {
        require(embeddings.isNotEmpty())
        val dim = embeddings[0].size
        val avg = FloatArray(dim)
        for (e in embeddings) {
            for (i in 0 until dim) avg[i] += e[i]
        }
        for (i in 0 until dim) avg[i] /= embeddings.size
        return normalize(avg)
    }

    private fun normalize(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm.toDouble()).toFloat().coerceAtLeast(1e-6f)
        return FloatArray(v.size) { v[it] / norm }
    }

    /**
     * OpenFace espera input en formato CHW, RGB, normalizado a [0, 1]
     * (a diferencia de ArcFace que usa [-1, 1] — revisar el preprocesamiento
     * exacto del export ONNX que uses; este es el estándar del repo original).
     */
    private fun bitmapToCHWTensor(bitmap: Bitmap): FloatBuffer {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val buffer = FloatBuffer.allocate(3 * w * h)
        for (p in pixels) buffer.put(((p shr 16) and 0xFF) / 255f) // R
        for (p in pixels) buffer.put(((p shr 8) and 0xFF) / 255f)  // G
        for (p in pixels) buffer.put((p and 0xFF) / 255f)          // B
        buffer.rewind()
        return buffer
    }

    fun close() {
        session.close()
    }
}