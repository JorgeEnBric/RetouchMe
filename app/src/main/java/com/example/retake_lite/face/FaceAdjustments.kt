package com.example.retake_lite.face

data class FaceAdjustments(
    val scale: Float = 1.0f,
    val rotationDegrees: Float = 0f,
    val offsetXRatio: Float = 0f,
    val offsetYRatio: Float = 0f,

    // --- Máscara / borde ---
    val edgeShrink: Float = 0f,
    val edgeShrinkLeft: Float = 0f,
    val edgeShrinkRight: Float = 0f,
    val edgeShrinkTop: Float = 0f,
    val edgeShrinkBottom: Float = 0f,
    val featherRadiusPx: Float = 9f,

    // --- Color ---
    val lightnessShift: Float = 0f,
    val redGreenShift: Float = 2.0f,
    val blueYellowShift: Float = 0f,
    val smoothing: Float = 0f
)
