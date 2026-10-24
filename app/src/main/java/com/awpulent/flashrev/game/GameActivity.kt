package com.awpulent.flashrev.game

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import com.awpulent.flashrev.FlashRevApp
import com.awpulent.flashrev.R
import com.awpulent.flashrev.audio.HitSounds
import com.awpulent.flashrev.audio.SongPlayer
import com.awpulent.flashrev.chart.Chart
import com.awpulent.flashrev.chart.Song
import com.awpulent.flashrev.demo.DemoInstaller
import com.awpulent.flashrev.ui.Fullscreen
import com.awpulent.flashrev.ui.ResultsActivity

class GameActivity : Activity(), GameView.Listener {

    private lateinit var gameView: GameView
    private lateinit var pauseOverlay: View
    private var player: SongPlayer? = null
    private var hitSounds: HitSounds? = null
    private lateinit var song: Song
    private lateinit var chart: Chart
    private var chartIndex = 0
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fullscreen.apply(window)
        setContentView(R.layout.activity_game)
        gameView = findViewById(R.id.game_view)
        pauseOverlay = findViewById(R.id.pause_overlay)
        gameView.listener = this

        findViewById<View>(R.id.btn_pause).setOnClickListener { pauseGame() }
        findViewById<View>(R.id.btn_resume).setOnClickListener { resumeGame() }
        findViewById<View>(R.id.btn_restart).setOnClickListener { restart() }
        findViewById<View>(R.id.btn_quit).setOnClickListener { finish() }

        val app = FlashRevApp.from(this)
        val songId = intent.getStringExtra(EXTRA_SONG_ID)
        chartIndex = intent.getIntExtra(EXTRA_CHART_INDEX, 0)
        val s = songId?.let { app.library.find(it) }
        val audio = s?.audioPath
        if (s == null || audio == null || chartIndex !in s.charts.indices) {
            Toast.makeText(this, getString(R.string.song_load_failed, songId ?: "?"), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        song = s
        chart = s.charts[chartIndex]

        // Preparing the MediaPlayer can take a moment; do it off the main thread.
        Thread {
            val result = runCatching {
                val p = SongPlayer(audio)
                val hs = if (app.settings.hitSounds) runCatching { HitSounds(DemoInstaller.tickFile(this)) }.getOrNull() else null
                p to hs
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) { result.getOrNull()?.let { it.first.release(); it.second?.release() }; return@runOnUiThread }
                result.onSuccess { (p, hs) ->
                    player = p
                    hitSounds = hs
                    gameView.start(chart, song.fullTitle, p, hs, GameView.Config.from(app.settings))
                }.onFailure { err ->
                    Toast.makeText(this, getString(R.string.song_load_failed, err.message ?: err.javaClass.simpleName), Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    private fun pauseGame() {
        if (finished || player == null) return
        gameView.pauseGame()
        pauseOverlay.visibility = View.VISIBLE
    }

    private fun resumeGame() {
        pauseOverlay.visibility = View.GONE
        gameView.resumeGame()
    }

    private fun restart() {
        val relaunch = Intent(this, GameActivity::class.java).putExtras(intent.extras ?: Bundle())
        finish()
        startActivity(relaunch)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (finished || player == null) { finish(); return }
        if (gameView.isPaused) resumeGame() else pauseGame()
    }

    override fun onPause() {
        super.onPause()
        if (!finished && player != null) pauseGame()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Fullscreen.apply(window)
    }

    override fun onDestroy() {
        super.onDestroy()
        gameView.stop()
        player?.release()
        hitSounds?.release()
        player = null
    }

    override fun onFinished(result: GameResult) {
        if (finished) return
        finished = true
        val app = FlashRevApp.from(this)
        val newBest = app.scores.submit(song.chartKey(chart), result)
        startActivity(
            Intent(this, ResultsActivity::class.java)
                .putExtra(ResultsActivity.EXTRA_RESULT, result.encode())
                .putExtra(ResultsActivity.EXTRA_SONG_ID, song.id)
                .putExtra(ResultsActivity.EXTRA_CHART_INDEX, chartIndex)
                .putExtra(ResultsActivity.EXTRA_NEW_BEST, newBest)
        )
        finish()
    }

    companion object {
        const val EXTRA_SONG_ID = "song_id"
        const val EXTRA_CHART_INDEX = "chart_index"

        fun intent(context: Context, song: Song, chartIndex: Int): Intent =
            Intent(context, GameActivity::class.java)
                .putExtra(EXTRA_SONG_ID, song.id)
                .putExtra(EXTRA_CHART_INDEX, chartIndex)
    }
}
