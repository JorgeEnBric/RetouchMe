package com.example.retouchme.face

import android.graphics.PointF
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceLandmark

object FaceLandmarkUtils {

    /** 5 puntos estándar (InsightFace / RetinaFace): ojos, nariz, comisuras. */
    fun fivePoints(face: Face): List<PointF>? {
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return null
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return null
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position ?: return null
        val mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
        val mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position

        if (mouthLeft != null && mouthRight != null) {
            return listOf(leftEye, rightEye, nose, mouthLeft, mouthRight)
        }

        val mouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position ?: return null
        val eyeDist = kotlin.math.hypot(
            (rightEye.x - leftEye.x).toDouble(),
            (rightEye.y - leftEye.y).toDouble()
        ).toFloat()
        val offset = eyeDist * 0.18f
        return listOf(
            leftEye,
            rightEye,
            nose,
            PointF(nose.x - offset, mouthBottom.y),
            PointF(nose.x + offset, mouthBottom.y)
        )
    }

    /**
     * Punto medio de la frente, derivado del contorno completo de la cara
     * (FaceContour.FACE). ML Kit no expone un landmark de "frente" directo,
     * así que se toma el punto más alto del contorno cuya X esté cerca del
     * centro horizontal de los ojos (evita tomar una esquina del óvalo).
     *
     * Esto reemplaza el antiguo "scaleBoost" artificial: en vez de agrandar
     * la cara con un factor inventado para "cubrir frente y mentón", se usa
     * la geometría real del contorno para que la transformación de similitud
     * ya contemple esa cobertura.
     */
    fun foreheadPoint(face: Face): PointF? {
        val contour = face.getContour(FaceContour.FACE)?.points ?: return null
        if (contour.isEmpty()) return null

        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val centerX = if (leftEye != null && rightEye != null) {
            (leftEye.x + rightEye.x) / 2f
        } else {
            contour.map { it.x }.average().toFloat()
        }

        // Banda central (±18% del ancho de cara) para evitar esquinas/orejas.
        val minX = contour.minOf { it.x }
        val maxX = contour.maxOf { it.x }
        val band = (maxX - minX) * 0.18f

        val candidates = contour.filter { kotlin.math.abs(it.x - centerX) <= band }
        val pool = if (candidates.isNotEmpty()) candidates else contour
        return pool.minByOrNull { it.y } // punto más alto = frente
    }

    /**
     * 6 puntos: los 5 estándar + frente. Usar este set en la transformación
     * de similitud da una escala/rotación que ya cubre frente y mentón de
     * forma natural, sin necesidad de aplicar un postScale artificial que
     * produce el efecto "zoom" poco profesional.
     *
     * Si no se puede obtener el contorno en alguno de los dos rostros
     * (origen o destino), se debe usar fivePoints() en ambos para mantener
     * la misma cantidad de puntos correspondientes.
     */
    fun sixPointsWithForehead(face: Face): List<PointF>? {
        val five = fivePoints(face) ?: return null
        val forehead = foreheadPoint(face) ?: return null
        // Orden: ojo izq, ojo der, frente, nariz, boca izq, boca der.
        return listOf(five[0], five[1], forehead, five[2], five[3], five[4])
    }
}