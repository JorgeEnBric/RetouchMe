package com.example.retake_lite.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker

class MediaPipeFaceMeshHelper(private val context: Context) {

    private var faceLandmarker: FaceLandmarker? = null

    init {
        try {
            faceLandmarker = FaceLandmarker.createFromFile(context, FACE_LANDMARKER_MODEL)
            Log.d(TAG, "FaceLandmarker created successfully from assets")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create FaceLandmarker", e)
            faceLandmarker = null
        }
    }

    fun detect(bitmap: Bitmap): List<List<PointF>>? {
        val landmarker = faceLandmarker
        if (landmarker == null) {
            Log.w(TAG, "FaceLandmarker not initialized")
            return null
        }
        return try {
            val argb = if (bitmap.config != Bitmap.Config.ARGB_8888) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else bitmap

            val mpImage = BitmapImageBuilder(argb).build()
            val result = landmarker.detect(mpImage)

            if (result == null) {
                Log.d(TAG, "detect() returned null")
                return null
            }

            val faceLandmarks = result.faceLandmarks()
            if (faceLandmarks.isEmpty()) {
                Log.d(TAG, "No faces detected")
                return emptyList()
            }

            Log.d(TAG, "Detected ${faceLandmarks.size} face(s) with ${faceLandmarks[0].size} landmarks each")
            faceLandmarks.map { face ->
                face.map { lm ->
                    PointF(lm.x() * bitmap.width, lm.y() * bitmap.height)
                }
            }.also {
                if (argb !== bitmap) argb.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Face detection failed", e)
            null
        }
    }

    fun detectFirstFace(bitmap: Bitmap): List<PointF>? {
        val all = detect(bitmap)
        val first = all?.firstOrNull()
        if (first != null) Log.d(TAG, "First face has ${first.size} landmarks")
        return first
    }

    fun isAvailable(): Boolean = faceLandmarker != null

    fun close() {
        faceLandmarker?.close()
        faceLandmarker = null
    }

    companion object {
        private const val TAG = "MediaPipeFaceMesh"
        private const val FACE_LANDMARKER_MODEL = "face_landmarker.task"
    }
}
