package com.awpulent.flashrev.game

/** Immutable summary of a finished (or abandoned) play. */
data class GameResult(
    val amazing: Int,
    val perfect: Int,
    val good: Int,
    val average: Int,
    val miss: Int,
    val boo: Int,
    val rawScore: Int,
    val score: Int,
    val maxCombo: Int,
    val totalNotes: Int,
    val completed: Boolean,
) {
    enum class Rank { AAA, SDG, FC, BLACKFLAG, CLEAR }

    val rank: Rank
        get() = when {
            good == 0 && average == 0 && miss == 0 && boo == 0 -> Rank.AAA
            good < 10 && average == 0 && miss == 0 && boo == 0 -> Rank.SDG
            miss == 0 && boo == 0 -> Rank.FC
            miss == 0 -> Rank.BLACKFLAG
            else -> Rank.CLEAR
        }

    /** Raw score as a fraction of the maximum possible raw score, clamped to 0..1. */
    val accuracy: Double
        get() = if (totalNotes == 0) 0.0 else (rawScore.toDouble() / (totalNotes * Judgment.AMAZING.rawPoints)).coerceIn(0.0, 1.0)

    val maxRawScore: Int get() = totalNotes * Judgment.AMAZING.rawPoints

    fun count(j: Judgment): Int = when (j) {
        Judgment.AMAZING -> amazing
        Judgment.PERFECT -> perfect
        Judgment.GOOD -> good
        Judgment.AVERAGE -> average
        Judgment.MISS -> miss
        Judgment.BOO -> boo
    }

    /** Compact serialisation for passing between activities and storing best scores. */
    fun encode(): String = listOf(
        amazing, perfect, good, average, miss, boo, rawScore, score, maxCombo, totalNotes, if (completed) 1 else 0
    ).joinToString(",")

    companion object {
        fun decode(s: String): GameResult? {
            val p = s.split(',').map { it.trim().toIntOrNull() ?: return null }
            if (p.size != 11) return null
            return GameResult(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8], p[9], p[10] == 1)
        }
    }
}
