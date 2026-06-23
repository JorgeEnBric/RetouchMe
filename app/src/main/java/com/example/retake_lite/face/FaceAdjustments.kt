package com.example.retake_lite.face

/**
 * Ajustes manuales que el usuario puede aplicar ENCIMA del resultado
 * automático del swap. Todos tienen un valor neutro (sin cambio) para que
 * "automático puro" sea simplemente AdjustableRetakeParams() por defecto.
 *
 * Diseñado para mapear 1:1 a sliders de UI.
 */
data class FaceAdjustments(
    // --- Geometría: corrige el "zoom" o desalineación del auto-swap ---
    /** 1.0 = sin cambio. Rango sugerido en UI: 0.7 - 1.4 */
    val scale: Float = 1.0f,
    /** Grados, sentido horario. Rango sugerido: -25 a 25 */
    val rotationDegrees: Float = 0f,
    /** Desplazamiento en píxeles, relativo al tamaño de la cara (no de la imagen completa). */
    val offsetXRatio: Float = 0f,
    val offsetYRatio: Float = 0f,

    // --- Máscara / borde: corrige el "halo" alrededor del rostro ---
    /**
     * Cuánto se contrae la máscara hacia el centro antes de difuminar,
     * en fracción del radio de la cara (0 = sin contraer, 0.15 = bastante).
     * Esto es justo lo que pediste: "difuminar el halo exterior hacia el centro".
     */
    val edgeShrink: Float = 0f,
    /** Radio de difuminado del borde de la máscara, en píxeles. Mayor = transición más suave. */
    val featherRadiusPx: Float = 18f,

    // --- Color: corrige el tinte verde / desajuste de tono ---
    /** Corrección manual en espacio LAB, aplicada DESPUÉS de la transferencia automática. */
    val lightnessShift: Float = 0f,  // L: -20..20
    val redGreenShift: Float = 0f,   // a: -20..20 (negativo = más verde, positivo = más rojo)
    val blueYellowShift: Float = 0f  // b: -20..20 (negativo = más azul, positivo = más amarillo)
)