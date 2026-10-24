package com.awpulent.flashrev.ui

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.awpulent.flashrev.FlashRevApp
import com.awpulent.flashrev.R
import com.awpulent.flashrev.audio.SongPlayer
import com.awpulent.flashrev.demo.DemoInstaller
import com.awpulent.flashrev.demo.DemoSongGenerator
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Plays the demo track and measures how late (or early) the player's taps land
 * relative to the beat, through the exact same audio path the game uses.
 */
class CalibrationActivity : Activity() {

    private var player: SongPlayer? = null
    private lateinit var view: CalibrationView
    private lateinit var measured: TextView
    private val offsets = ArrayList<Double>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fullscreen.apply(window)
        setContentView(R.layout.activity_calibration)
        measured = findViewById(R.id.measured)
        view = findViewById(R.id.calibration_view)
        view.onTap = { eventTime -> registerTap(eventTime) }
        findViewById<View>(R.id.btn_save).setOnClickListener { save() }
        findViewById<View>(R.id.btn_cancel).setOnClickListener { finish() }
        measured.text = getString(R.string.calibration_waiting)

        val audio = File(DemoInstaller.demoDir(this), DemoSongGenerator.AUDIO_FILE)
        Thread {
            val p = runCatching { SongPlayer(audio.absolutePath) }.getOrNull()
            runOnUiThread {
                if (isDestroyed) { p?.release(); return@runOnUiThread }
                if (p == null) { Toast.makeText(this, getString(R.string.song_load_failed, audio.name), Toast.LENGTH_LONG).show(); finish(); return@runOnUiThread }
                player = p
                view.player = p
                p.start()
            }
        }.start()
    }

    private fun registerTap(eventTime: Long) {
        val p = player ?: return
        val t = p.songTimeAt(eventTime) - DemoSongGenerator.LEAD_IN_SECONDS
        if (t < 0) return
        val beat = 60.0 / DemoSongGenerator.BPM
        val nearest = (t / beat).roundToInt() * beat
        val error = (t - nearest) * 1000.0
        if (abs(error) > 200) return // wildly off: ignore
        offsets.add(error)
        if (offsets.size > 24) offsets.removeAt(0)
        val mean = currentMean()
        measured.text = getString(R.string.calibration_measured, mean.roundToInt(), offsets.size)
        view.flash()
    }

    /** Trimmed mean: drop the worst outliers once there are enough samples. */
    private fun currentMean(): Double {
        if (offsets.isEmpty()) return 0.0
        val sorted = offsets.sorted()
        val trim = if (sorted.size >= 8) sorted.size / 8 else 0
        val core = sorted.subList(trim, sorted.size - trim)
        return core.average()
    }

    private fun save() {
        if (offsets.size < 4) { Toast.makeText(this, R.string.calibration_waiting, Toast.LENGTH_SHORT).show(); return }
        FlashRevApp.from(this).settings.globalOffsetMs = currentMean().roundToInt()
        finish()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        player?.let { if (!it.completed) it.start() }
    }

    override fun onDestroy() {
        super.onDestroy()
        view.player = null
        player?.release()
        player = null
    }

    /** Pulses a ring on every beat and reports taps with their uptime timestamps. */
    class CalibrationView(context: Context, attrs: android.util.AttributeSet? = null) : View(context, attrs) {
        var player: SongPlayer? = null
        var onTap: ((Long) -> Unit)? = null
        private var flashUptime = 0L
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8f; color = 0xFFFFD040.toInt() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val frame = object : Runnable { override fun run() { invalidate(); postOnAnimation(this) } }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); postOnAnimation(frame) }
        override fun onDetachedFromWindow() { removeCallbacks(frame); super.onDetachedFromWindow() }

        fun flash() { flashUptime = SystemClock.uptimeMillis() }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                onTap?.invoke(event.eventTime)
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
            val cx = width / 2f
            val cy = height / 2f
            val base = minOf(width, height) * 0.18f
            val p = player
            val t = if (p != null && p.isPlaying) p.songTimeNow() - DemoSongGenerator.LEAD_IN_SECONDS else -1.0
            val beat = 60.0 / DemoSongGenerator.BPM
            val phase = if (t >= 0) ((t % beat) / beat).toFloat() else 1f
            val radius = base * (1f + 0.6f * (1f - phase))
            ring.alpha = (255 * (1f - phase * 0.7f)).toInt()
            canvas.drawCircle(cx, cy, radius, ring)
            val age = SystemClock.uptimeMillis() - flashUptime
            if (age in 0..150) {
                fill.color = Color.argb((180 * (1 - age / 150f)).toInt(), 255, 255, 255)
                canvas.drawCircle(cx, cy, base * 0.9f, fill)
            }
        }
    }
}
