package com.example.retake_lite.face

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import com.google.mlkit.vision.face.Face

/**
 * OpenFace espera una alineación específica: lienzo de 96x96 con los ojos
 * y la nariz en posiciones fijas (basado en cómo CMU alineó su dataset de
 * entrenamiento con dlib). Si la alineación no coincide con ese template,
 * el embedding pierde precisión — por eso no se puede simplemente recortar
 * el bounding box de la cara.
 */
object FaceAligner {

    /**
     * Template de OpenFace (nn4.small2.v1), lienzo 96x96.
     * Orden: ojo izquierdo, ojo derecho, nariz.
     * Valores estándar usados por el proyecto OpenFace (CMU) para su
     * "AffineAlign" de 3 puntos.
     */
    private val OPENFACE_96_TEMPLATE = listOf(
        PointF(28.9946f, 31.6963f), // ojo izquierdo
        PointF(67.0000f, 31.6963f), // ojo derecho
        PointF(48.0252f, 64.0000f)  // nariz
    )

    data class AlignedFace(
        val bitmap: Bitmap,
        val forwardMatrix: Matrix,
        val inverseMatrix: Matrix
    )

    /** Alinea para OpenFace: usa 3 puntos (ojos + nariz), lienzo 96x96. */
    fun alignForOpenFace(source: Bitmap, face: Face): AlignedFace? {
        val five = FaceLandmarkUtils.fivePoints(face) ?: return null
        val threePoints = listOf(five[0], five[1], five[2]) // ojo izq, ojo der, nariz

        val matrix = SimilarityTransform.estimate(threePoints, OPENFACE_96_TEMPLATE) ?: return null

        val out = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, matrix, paint)

        val inverse = Matrix()
        if (!matrix.invert(inverse)) return null

        return AlignedFace(out, matrix, inverse)
    }
}