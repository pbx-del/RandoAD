package com.example.montee

import kotlinx.coroutines.flow.MutableStateFlow

/** Une ligne d'affichage : fenêtre (ou global), vitesse en m/h, dénivelé net en m. */
data class Row(val label: String, val rate: Double?, val delta: Double?)

data class Stats(
    val status: String = "Arrêté",
    val altitude: Double? = null,
    val rows: List<Row> = emptyList(),
    val dPlus: Double = 0.0,
    val dMinus: Double = 0.0,
    val distance: Double = 0.0,
    val alarm: Boolean = false
)

object ClimbState {
    val stats = MutableStateFlow(Stats())
    val running = MutableStateFlow(false)
    /** Seuil d'alarme sur la vitesse 5 min, en m/h (0 = alarme désactivée). */
    val threshold = MutableStateFlow(0)
}
