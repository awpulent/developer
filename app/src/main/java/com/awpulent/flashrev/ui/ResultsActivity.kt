package com.awpulent.flashrev.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.awpulent.flashrev.FlashRevApp
import com.awpulent.flashrev.R
import com.awpulent.flashrev.game.GameActivity
import com.awpulent.flashrev.game.GameResult

class ResultsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_results)
        findViewById<View>(R.id.root).padForSystemBars()

        val result = intent.getStringExtra(EXTRA_RESULT)?.let { GameResult.decode(it) }
        val songId = intent.getStringExtra(EXTRA_SONG_ID)
        val chartIndex = intent.getIntExtra(EXTRA_CHART_INDEX, 0)
        val newBest = intent.getBooleanExtra(EXTRA_NEW_BEST, false)
        if (result == null || songId == null) { finish(); return }

        val app = FlashRevApp.from(this)
        val song = app.library.find(songId)
        val chart = song?.charts?.getOrNull(chartIndex)

        findViewById<TextView>(R.id.song_title).text = song?.fullTitle ?: songId
        findViewById<TextView>(R.id.chart_name).text =
            chart?.let { getString(R.string.difficulty_meter, it.difficulty.displayName, it.meter) } ?: ""
        findViewById<TextView>(R.id.rank).apply {
            text = when (result.rank) {
                GameResult.Rank.AAA -> getString(R.string.rank_aaa)
                GameResult.Rank.SDG -> getString(R.string.rank_sdg)
                GameResult.Rank.FC -> getString(R.string.rank_fc)
                GameResult.Rank.BLACKFLAG -> getString(R.string.rank_blackflag)
                GameResult.Rank.CLEAR -> getString(R.string.rank_clear)
            }
            setTextColor(
                getColor(
                    when (result.rank) {
                        GameResult.Rank.AAA -> R.color.judge_amazing
                        GameResult.Rank.SDG -> R.color.judge_perfect
                        GameResult.Rank.FC, GameResult.Rank.BLACKFLAG -> R.color.judge_good
                        GameResult.Rank.CLEAR -> R.color.text_secondary
                    }
                )
            )
        }
        findViewById<TextView>(R.id.new_best).visibility = if (newBest) View.VISIBLE else View.GONE
        bindRow(R.id.row_score, R.string.score, String.format("%,d", result.score))
        bindRow(R.id.row_raw, R.string.raw_score, "${String.format("%,d", result.rawScore)} / ${String.format("%,d", result.maxRawScore)}")
        bindRow(R.id.row_accuracy, R.string.accuracy, String.format("%.2f%%", result.accuracy * 100))
        bindRow(R.id.row_combo, R.string.max_combo, result.maxCombo.toString())
        bindRow(R.id.row_amazing, R.string.amazing, result.amazing.toString(), R.color.judge_amazing)
        bindRow(R.id.row_perfect, R.string.perfect, result.perfect.toString(), R.color.judge_perfect)
        bindRow(R.id.row_good, R.string.good, result.good.toString(), R.color.judge_good)
        bindRow(R.id.row_average, R.string.average, result.average.toString(), R.color.judge_average)
        bindRow(R.id.row_miss, R.string.miss, result.miss.toString(), R.color.judge_miss)
        bindRow(R.id.row_boo, R.string.boo, result.boo.toString(), R.color.judge_boo)

        findViewById<View>(R.id.btn_retry).apply {
            isEnabled = song != null && chart != null
            setOnClickListener {
                if (song != null) { startActivity(GameActivity.intent(this@ResultsActivity, song, chartIndex)); finish() }
            }
        }
        findViewById<View>(R.id.btn_songs).setOnClickListener { finish() }
    }

    private fun bindRow(rowId: Int, labelRes: Int, value: String, colorRes: Int = 0) {
        val row = findViewById<View>(rowId)
        row.findViewById<TextView>(R.id.stat_label).apply {
            setText(labelRes)
            if (colorRes != 0) setTextColor(getColor(colorRes))
        }
        row.findViewById<TextView>(R.id.stat_value).text = value
    }

    companion object {
        const val EXTRA_RESULT = "result"
        const val EXTRA_SONG_ID = "song_id"
        const val EXTRA_CHART_INDEX = "chart_index"
        const val EXTRA_NEW_BEST = "new_best"
    }
}
