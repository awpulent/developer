package com.awpulent.flashrev.data

import android.content.Context
import android.content.SharedPreferences
import com.awpulent.flashrev.game.GameResult

/** Best result per chart, keyed by [com.awpulent.flashrev.chart.Song.chartKey]. */
class ScoreStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("scores", Context.MODE_PRIVATE)

    fun best(chartKey: String): GameResult? = prefs.getString(chartKey, null)?.let { GameResult.decode(it) }

    /**
     * Stores [result] if it beats the current best. Only completed plays count.
     * Returns true when a new best was recorded.
     */
    fun submit(chartKey: String, result: GameResult): Boolean {
        if (!result.completed) return false
        val current = best(chartKey)
        if (current != null && !isBetter(result, current)) return false
        prefs.edit().putString(chartKey, result.encode()).apply()
        return true
    }

    fun clear(chartKeyPrefix: String) {
        val editor = prefs.edit()
        for (key in prefs.all.keys) if (key.startsWith(chartKeyPrefix)) editor.remove(key)
        editor.apply()
    }

    private fun isBetter(a: GameResult, b: GameResult): Boolean =
        a.rawScore > b.rawScore || (a.rawScore == b.rawScore && a.maxCombo > b.maxCombo)
}
