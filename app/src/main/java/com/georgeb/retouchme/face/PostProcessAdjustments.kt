package com.georgeb.retouchme.face

data class PostProcessAdjustments(
    val faceBrightness: Float = 0f,
    val faceContrast: Float = 0f,
    val faceSaturation: Float = 0f,
    val faceWarmth: Float = 0f,
    val faceSmoothing: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val gamma: Float = 1f,
    val warmth: Float = 0f,
    val sharpness: Float = 0f
)
