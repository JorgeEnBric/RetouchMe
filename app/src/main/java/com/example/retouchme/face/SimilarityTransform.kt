package com.example.retouchme.face

import android.graphics.Matrix
import android.graphics.PointF
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Estima transformación de similitud (escala + rotación + traslación) entre dos
 * conjuntos de landmarks — mismo principio que InsightFace norm_crop.
 */
object SimilarityTransform {

    fun estimate(src: List<PointF>, dst: List<PointF>): Matrix? {
        if (src.size != dst.size || src.size < 2) return null

        val n = src.size
        var srcCx = 0f
        var srcCy = 0f
        var dstCx = 0f
        var dstCy = 0f
        src.forEach { srcCx += it.x; srcCy += it.y }
        dst.forEach { dstCx += it.x; dstCy += it.y }
        srcCx /= n
        srcCy /= n
        dstCx /= n
        dstCy /= n

        var varSrc = 0.0
        var covA = 0.0
        var covB = 0.0
        for (i in 0 until n) {
            val sx = (src[i].x - srcCx).toDouble()
            val sy = (src[i].y - srcCy).toDouble()
            val dx = (dst[i].x - dstCx).toDouble()
            val dy = (dst[i].y - dstCy).toDouble()
            varSrc += sx * sx + sy * sy
            covA += dx * sx + dy * sy
            covB += dx * (-sy) + dy * sx
        }
        if (varSrc < 1e-6) return null

        val scale = hypot(covA, covB) / varSrc
        val angle = atan2(covB, covA)
        val c = cos(angle) * scale
        val s = sin(angle) * scale

        return Matrix().apply {
            setValues(
                floatArrayOf(
                    c.toFloat(), (-s).toFloat(), (dstCx - (c * srcCx - s * srcCy)).toFloat(),
                    s.toFloat(), c.toFloat(), (dstCy - (s * srcCx + c * srcCy)).toFloat(),
                    0f, 0f, 1f
                )
            )
        }
    }
}
