package com.georgeb.retouchme.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

data class DetectedFace(
    val face: Face,
    val cropPath: String,
    val leftEyeX: Float,
    val leftEyeY: Float,
    val rightEyeX: Float,
    val rightEyeY: Float,
    val noseX: Float,
    val noseY: Float
)

class FaceDetectorHelper(context: Context) {

    private val facesDir = File(context.filesDir, "faces").apply { mkdirs() }

    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .enableTracking()
            .build()
    )

    suspend fun detectRawFaces(bitmap: Bitmap): List<Face> = detect(bitmap)

    suspend fun detectFaces(bitmap: Bitmap): List<DetectedFace> {
        val faces = detect(bitmap)
        return faces.mapNotNull { face -> toDetectedFace(bitmap, face) }
    }

    private suspend fun detect(bitmap: Bitmap): List<Face> =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            detector.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

    private fun toDetectedFace(bitmap: Bitmap, face: Face): DetectedFace? {
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return null
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return null
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position ?: return null

        val padding = (face.boundingBox.width() * 0.45f).toInt()
        val cropRect = expandRect(face.boundingBox, padding, bitmap.width, bitmap.height)
        val crop = Bitmap.createBitmap(
            bitmap,
            cropRect.left,
            cropRect.top,
            cropRect.width(),
            cropRect.height()
        )

        val path = saveFaceCrop(crop)
        crop.recycle()

        return DetectedFace(
            face = face,
            cropPath = path,
            leftEyeX = leftEye.x - cropRect.left,
            leftEyeY = leftEye.y - cropRect.top,
            rightEyeX = rightEye.x - cropRect.left,
            rightEyeY = rightEye.y - cropRect.top,
            noseX = nose.x - cropRect.left,
            noseY = nose.y - cropRect.top
        )
    }

    private fun expandRect(rect: Rect, padding: Int, maxW: Int, maxH: Int): Rect {
        val left = max(0, rect.left - padding)
        val top = max(0, rect.top - padding)
        val right = min(maxW, rect.right + padding)
        val bottom = min(maxH, rect.bottom + padding)
        return Rect(left, top, right, bottom)
    }

    private fun saveFaceCrop(bitmap: Bitmap): String {
        val file = File(facesDir, "${UUID.randomUUID()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        return file.absolutePath
    }

    fun close() {
        detector.close()
    }
}
