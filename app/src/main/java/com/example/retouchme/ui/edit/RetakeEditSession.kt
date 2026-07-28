package com.example.retouchme.ui.edit

import com.example.retouchme.face.AutoRetakeResult
import com.example.retouchme.face.FaceAdjustments
import com.example.retouchme.face.FaceRetakeEngine

/**
 * Puente en memoria entre FaceSwapActivity y RetakeEditActivity.
 * Los Bitmap no deben pasarse por Intent (límite de tamaño del Binder),
 * así que se guardan aquí temporalmente mientras dura la edición manual.
 *
 * Uso:
 *  1. FaceSwapActivity llama RetakeEditSession.start(engine, auto) y abre
 *     RetakeEditActivity.
 *  2. RetakeEditActivity lee RetakeEditSession.engine/auto en onCreate.
 *  3. Al cerrar (Aplicar o Cancelar), RetakeEditActivity llama
 *     RetakeEditSession.clear() para soltar las referencias.
 */
object RetakeEditSession {
    var engine: FaceRetakeEngine? = null
        private set
    var auto: AutoRetakeResult? = null
        private set
    var lastAdjustments: FaceAdjustments = FaceAdjustments()
    var magicHaloAutoApplied: Boolean = false

    /** Callback que RetakeEditActivity invoca con el bitmap final al pulsar "Aplicar". */
    var onApplied: ((android.graphics.Bitmap) -> Unit)? = null

    fun start(
        engine: FaceRetakeEngine,
        auto: AutoRetakeResult,
        initialAdjustments: FaceAdjustments = FaceAdjustments(),
        onApplied: (android.graphics.Bitmap) -> Unit
    ) {
        this.engine = engine
        this.auto = auto
        this.lastAdjustments = initialAdjustments
        this.magicHaloAutoApplied = false
        this.onApplied = onApplied
    }

    fun clear() {
        auto?.let { engine?.releaseAutoResult(it) }
        engine = null
        auto = null
        onApplied = null
    }
}