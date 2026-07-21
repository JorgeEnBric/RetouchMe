package com.example.retake_lite.face

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import android.graphics.Bitmap.Config
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

object FaceMaskBuilder {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    fun createFaceMask(
        width: Int,
        height: Int,
        face: Face?,
        edgeShrink: Float = 0f,
        edgeShrinkLeft: Float = 0f,
        edgeShrinkRight: Float = 0f,
        edgeShrinkTop: Float = 0f,
        edgeShrinkBottom: Float = 0f
    ): Bitmap {
        if (face == null) return createFullMask(width, height)
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(mask)

        val contour = face.getContour(FaceContour.FACE)
        if (contour != null && contour.points.size >= 3) {
            canvas.drawPath(
                expandContourDirectional(
                    contour.points,
                    edgeShrink, edgeShrinkLeft, edgeShrinkRight, edgeShrinkTop, edgeShrinkBottom
                ),
                fillPaint
            )
        } else {
            val box = face.boundingBox
            val cx = box.centerX().toFloat()
            val cy = box.centerY().toFloat()
            val shrinkH = (edgeShrink + edgeShrinkLeft + edgeShrinkRight).coerceIn(0f, 0.8f)
            val shrinkV = (edgeShrink + edgeShrinkTop + edgeShrinkBottom).coerceIn(0f, 0.8f)
            val rx = box.width() * (0.55f - shrinkH * 0.5f)
            val ry = box.height() * (0.62f - shrinkV * 0.5f)
            canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, fillPaint)
        }

        return mask
    }

    fun createFullMask(width: Int, height: Int): Bitmap {
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(mask).drawColor(Color.WHITE)
        return mask
    }

    private fun expandContourDirectional(
        points: List<PointF>,
        general: Float,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float
    ): Path {
        val cx = points.map { it.x }.average().toFloat()
        val cy = points.map { it.y }.average().toFloat()
        val halfW = (points.maxOf { it.x } - points.minOf { it.x }) / 2f
        val halfH = (points.maxOf { it.y } - points.minOf { it.y }) / 2f

        val path = Path()
        points.forEachIndexed { i, p ->
            val shrinkX = general + when {
                p.x < cx -> left
                p.x > cx -> right
                else -> (left + right) / 2f
            }
            val shrinkY = general + when {
                p.y < cy -> top
                p.y > cy -> bottom
                else -> (top + bottom) / 2f
            }
            val factorX = (1.10f - shrinkX.coerceIn(0f, 0.8f)).coerceAtLeast(0.5f)
            val factorY = (1.10f - shrinkY.coerceIn(0f, 0.8f)).coerceAtLeast(0.5f)
            val x = cx + (p.x - cx) * factorX
            val y = cy + (p.y - cy) * factorY
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }
}

object LaplacianBlender {

    fun blend(base: Bitmap, overlay: Bitmap, mask: Bitmap, featherRadiusPx: Float = 9f): Bitmap {
        require(base.width == overlay.width && base.height == overlay.height)
        require(base.width == mask.width && base.height == mask.height)

        val w = base.width
        val h = base.height
        val passes = (featherRadiusPx / 5f).toInt().coerceIn(0, 8)
        val softMask = if (passes == 0) mask else blurMaskRepeated(mask, passes)

        val basePx = IntArray(w * h)
        val overlayPx = IntArray(w * h)
        val maskPx = IntArray(w * h)
        base.getPixels(basePx, 0, w, 0, 0, w, h)
        overlay.getPixels(overlayPx, 0, w, 0, 0, w, h)
        softMask.getPixels(maskPx, 0, w, 0, 0, w, h)

        val outPx = IntArray(w * h)
        for (i in outPx.indices) {
            val m = Color.alpha(maskPx[i]) / 255f
            if (m < 0.02f) {
                outPx[i] = basePx[i]
                continue
            }
            val o = overlayPx[i]
            val oAlpha = Color.alpha(o) / 255f
            if (oAlpha < 0.05f) {
                outPx[i] = basePx[i]
                continue
            }
            val weight = m.coerceIn(0f, 1f)
            val b = basePx[i]
            outPx[i] = Color.argb(
                255,
                lerp(Color.red(b), Color.red(o), weight),
                lerp(Color.green(b), Color.green(o), weight),
                lerp(Color.blue(b), Color.blue(o), weight)
            )
        }

        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        out.setPixels(outPx, 0, w, 0, 0, w, h)
        if (softMask !== mask) softMask.recycle()
        return out
    }

    fun clipOverlayToMask(overlay: Bitmap, mask: Bitmap): Bitmap {
        val w = overlay.width
        val h = overlay.height
        val oPx = IntArray(w * h)
        val mPx = IntArray(w * h)
        overlay.getPixels(oPx, 0, w, 0, 0, w, h)
        mask.getPixels(mPx, 0, w, 0, 0, w, h)

        for (i in oPx.indices) {
            val m = Color.alpha(mPx[i])
            if (m < 8) {
                oPx[i] = Color.TRANSPARENT
            } else {
                val a = (Color.alpha(oPx[i]) * m / 255f).toInt().coerceIn(0, 255)
                oPx[i] = Color.argb(a, Color.red(oPx[i]), Color.green(oPx[i]), Color.blue(oPx[i]))
            }
        }
        val out = overlay.copy(Bitmap.Config.ARGB_8888, true)
        out.setPixels(oPx, 0, w, 0, 0, w, h)
        return out
    }

    fun denoiseTransition(base: Bitmap, blended: Bitmap, mask: Bitmap): Bitmap {
        val w = blended.width; val h = blended.height
        val px = IntArray(w * h); blended.getPixels(px, 0, w, 0, 0, w, h)
        val maskPx = IntArray(w * h); mask.getPixels(maskPx, 0, w, 0, 0, w, h)

        val blurred = IntArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val m = Color.alpha(maskPx[idx])
                if (m in 16..239) {
                    var r = 0; var g = 0; var b = 0; var c = 0
                    for (dy in -1..1) for (dx in -1..1) {
                        val n = (y + dy) * w + (x + dx)
                        r += Color.red(px[n]); g += Color.green(px[n]); b += Color.blue(px[n]); c++
                    }
                    blurred[idx] = Color.rgb(r / c, g / c, b / c)
                } else {
                    blurred[idx] = px[idx]
                }
            }
        }
        val out = blended.copy(Bitmap.Config.ARGB_8888, true)
        out.setPixels(blurred, 0, w, 0, 0, w, h)
        if (out !== blended) blended.recycle()
        return out
    }

    fun poissonBlend(base: Bitmap, overlay: Bitmap, mask: Bitmap, centerX: Int, centerY: Int): Bitmap {
        val srcRgba = Mat()
        val dstRgba = Mat()
        val maskRgba = Mat()
        val srcBgr = Mat()
        val dstBgr = Mat()
        val maskGray = Mat()
        val resultBgr = Mat()
        val kernel = Mat()

        Utils.bitmapToMat(overlay, srcRgba)
        Utils.bitmapToMat(base, dstRgba)
        Utils.bitmapToMat(mask, maskRgba)

        Imgproc.cvtColor(srcRgba, srcBgr, Imgproc.COLOR_RGBA2BGR)
        Imgproc.cvtColor(dstRgba, dstBgr, Imgproc.COLOR_RGBA2BGR)
        Core.extractChannel(maskRgba, maskGray, 0)

        // Dilatar máscara 5 px para cubrir posibles huecos en la superposición
        kernel.create(5, 5, org.opencv.core.CvType.CV_8U)
        kernel.setTo(org.opencv.core.Scalar(1.0))
        Imgproc.dilate(maskGray, maskGray, kernel)
        kernel.release()

        val center = Point(centerX.toDouble(), centerY.toDouble())
        Photo.seamlessClone(srcBgr, dstBgr, maskGray, center, resultBgr, Photo.NORMAL_CLONE)

        val resultRgba = Mat()
        Imgproc.cvtColor(resultBgr, resultRgba, Imgproc.COLOR_BGR2RGBA)

        val out = base.copy(Config.ARGB_8888, true)
        Utils.matToBitmap(resultRgba, out)

        srcRgba.release(); dstRgba.release(); maskRgba.release()
        srcBgr.release(); dstBgr.release(); maskGray.release(); resultBgr.release(); resultRgba.release()
        return out
    }

    private fun lerp(a: Int, b: Int, t: Float): Int =
        (a + (b - a) * t).toInt().coerceIn(0, 255)

    private fun blurMaskRepeated(mask: Bitmap, passes: Int): Bitmap {
        var current = mask
        repeat(passes) {
            val blurred = blurMaskOnce(current)
            if (current !== mask) current.recycle()
            current = blurred
        }
        return current
    }

    private fun blurMaskOnce(mask: Bitmap): Bitmap {
        val w = mask.width
        val h = mask.height
        val src = IntArray(w * h)
        mask.getPixels(src, 0, w, 0, 0, w, h)
        val dst = IntArray(w * h)
        val radius = 5

        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                var count = 0
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx in 0 until w && ny in 0 until h) {
                            sum += Color.alpha(src[ny * w + nx])
                            count++
                        }
                    }
                }
                dst[y * w + x] = Color.argb(if (count > 0) sum / count else 0, 255, 255, 255)
            }
        }

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(dst, 0, w, 0, 0, w, h)
        }
    }
}
