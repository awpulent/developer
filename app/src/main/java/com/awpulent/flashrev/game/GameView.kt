package com.awpulent.flashrev.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.awpulent.flashrev.R
import com.awpulent.flashrev.audio.HitSounds
import com.awpulent.flashrev.audio.SongPlayer
import com.awpulent.flashrev.chart.Chart
import com.awpulent.flashrev.chart.Snap
import com.awpulent.flashrev.data.Settings
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The play field: renders scrolling arrows on a SurfaceView from a dedicated thread,
 * maps touches to lanes and feeds the [GameEngine].
 */
class GameView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    SurfaceView(context, attrs), SurfaceHolder.Callback {

    interface Listener {
        /** Called on the main thread once the song has been fully judged. */
        fun onFinished(result: GameResult)
    }

    class Config(
        val scrollSpeed: Float,
        val scrollUp: Boolean,
        val offsetSeconds: Double,
        val showEarlyLate: Boolean,
        val snapColors: Boolean,
        val laneWidthPercent: Int,
    ) {
        companion object {
            fun from(settings: Settings) = Config(
                scrollSpeed = settings.scrollSpeed,
                scrollUp = settings.scrollDirection == Settings.ScrollDirection.UP,
                offsetSeconds = settings.globalOffsetMs / 1000.0,
                showEarlyLate = settings.showEarlyLate,
                snapColors = settings.snapColors,
                laneWidthPercent = settings.laneWidthPercent,
            )
        }
    }

    private enum class Phase { IDLE, PREROLL, PLAYING, PAUSED, COUNTDOWN, FINISHED }

    // ------------------------------------------------------------ session state
    private val lock = Any()
    private var engine: GameEngine? = null
    private var player: SongPlayer? = null
    private var hitSounds: HitSounds? = null
    private var config: Config = Config(2f, true, 0.0, true, true, 80)
    private var songTitle: String = ""
    var listener: Listener? = null

    @Volatile private var phase = Phase.IDLE
    private var phaseStartUptime = 0L
    private var preRollStart = 0.0        // song-seconds at which the pre-roll began (negative)
    private var pausedSongTime = 0.0
    private var countdownEndUptime = 0L
    private var lastNoteTime = 0.0
    private var finishNotified = false
    private var resumeAfterCountdown = false

    // ------------------------------------------------------------ feedback state
    private var lastJudgment: Judgment? = null
    private var lastJudgmentUptime = 0L
    private var lastErrorMs = 0.0
    private val laneFlashUptime = LongArray(4)
    private val laneHitUptime = LongArray(4)
    private val laneHitJudgment = arrayOfNulls<Judgment>(4)
    private var scoreText = "0"
    private var rawText = "0"
    private var cachedScore = -1
    private var cachedRaw = Int.MIN_VALUE

    // ------------------------------------------------------------ render thread
    @Volatile private var running = false
    @Volatile private var surfaceReady = false
    private var thread: Thread? = null

    // ------------------------------------------------------------ geometry
    private var w = 1f
    private var h = 1f
    private var laneWidth = 1f
    private var lanesLeft = 0f
    private var arrowSize = 1f
    private var receptorY = 0f
    private var pixelsPerSecond = 1f
    private var dirSign = 1f // +1: future notes are below the receptor (scroll up); -1: above
    private val arrowPaths = Array(4) { Path() }
    private val unitArrow = Path()
    private val matrix = Matrix()
    private val laneCenterX = FloatArray(4)
    private var drawCursor = 0

    // ------------------------------------------------------------ paints
    private val bgPaint = Paint().apply { color = Color.BLACK }
    private val lanePaint = Paint().apply { color = 0x14FFFFFF }
    private val dividerPaint = Paint().apply { color = 0x22FFFFFF; strokeWidth = 1f }
    private val receptorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF8A93B5.toInt()
    }
    private val receptorFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val noteOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF101018.toInt()
    }
    private val noteHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x55FFFFFF }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER
    }
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD; color = 0xFFE8ECFF.toInt()
    }
    private val hudDimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8A93B5.toInt() }
    private val progressPaint = Paint().apply { color = 0xFFFFD040.toInt() }
    private val overlayPaint = Paint().apply { color = 0x99000000.toInt() }
    private val tmpRect = RectF()

    private val snapColors = IntArray(Snap.entries.size)
    private val judgeColors = IntArray(Judgment.entries.size)

    init {
        holder.addCallback(this)
        isFocusable = true
        val c = context
        snapColors[Snap.QUARTER.ordinal] = c.getColor(R.color.note_4th)
        snapColors[Snap.EIGHTH.ordinal] = c.getColor(R.color.note_8th)
        snapColors[Snap.TWELFTH.ordinal] = c.getColor(R.color.note_12th)
        snapColors[Snap.SIXTEENTH.ordinal] = c.getColor(R.color.note_16th)
        snapColors[Snap.TWENTY_FOURTH.ordinal] = c.getColor(R.color.note_24th)
        snapColors[Snap.THIRTY_SECOND.ordinal] = c.getColor(R.color.note_32nd)
        snapColors[Snap.FORTY_EIGHTH.ordinal] = c.getColor(R.color.note_48th)
        snapColors[Snap.SIXTY_FOURTH.ordinal] = c.getColor(R.color.note_64th)
        snapColors[Snap.OTHER.ordinal] = c.getColor(R.color.note_other)
        judgeColors[Judgment.AMAZING.ordinal] = c.getColor(R.color.judge_amazing)
        judgeColors[Judgment.PERFECT.ordinal] = c.getColor(R.color.judge_perfect)
        judgeColors[Judgment.GOOD.ordinal] = c.getColor(R.color.judge_good)
        judgeColors[Judgment.AVERAGE.ordinal] = c.getColor(R.color.judge_average)
        judgeColors[Judgment.MISS.ordinal] = c.getColor(R.color.judge_miss)
        judgeColors[Judgment.BOO.ordinal] = c.getColor(R.color.judge_boo)

        // Unit arrow pointing up, centred on the origin, 1 unit tall/wide.
        unitArrow.apply {
            moveTo(0f, -0.5f); lineTo(0.5f, 0f); lineTo(0.22f, 0f); lineTo(0.22f, 0.5f)
            lineTo(-0.22f, 0.5f); lineTo(-0.22f, 0f); lineTo(-0.5f, 0f); close()
        }
    }

    // ------------------------------------------------------------ public API

    /** Binds a session. Must be called before the surface is created or while idle. */
    fun start(chart: Chart, songTitle: String, player: SongPlayer, hitSounds: HitSounds?, config: Config) {
        synchronized(lock) {
            this.engine = GameEngine(chart)
            this.player = player
            this.hitSounds = hitSounds
            this.config = config
            this.songTitle = songTitle
            lastNoteTime = chart.notes.lastOrNull()?.time ?: 0.0
            val firstNote = chart.notes.firstOrNull()?.time ?: 0.0
            preRollStart = min(-PREROLL_SECONDS, firstNote - PREROLL_SECONDS)
            phaseStartUptime = SystemClock.uptimeMillis()
            finishNotified = false
            drawCursor = 0
            phase = Phase.PREROLL
        }
        computeGeometry()
        startThread()
    }

    val isPaused: Boolean get() = phase == Phase.PAUSED

    /** Pauses the song and freezes the field. Safe to call repeatedly. */
    fun pauseGame() {
        synchronized(lock) {
            when (phase) {
                Phase.PLAYING -> { player?.pause(); pausedSongTime = player?.songTimeNow() ?: 0.0; phase = Phase.PAUSED }
                Phase.PREROLL -> { pausedSongTime = clockAt(SystemClock.uptimeMillis()); phase = Phase.PAUSED }
                Phase.COUNTDOWN -> { phase = Phase.PAUSED }
                else -> {}
            }
        }
    }

    /** Resumes after a pause with a short countdown. */
    fun resumeGame() {
        synchronized(lock) {
            if (phase != Phase.PAUSED) return
            phase = Phase.COUNTDOWN
            countdownEndUptime = SystemClock.uptimeMillis() + COUNTDOWN_MS
            resumeAfterCountdown = true
        }
    }

    /** Current result snapshot (marks unplayed notes as missed if [abandon]). */
    fun currentResult(abandon: Boolean): GameResult? = synchronized(lock) {
        val e = engine ?: return null
        if (abandon) e.abandon()
        e.result(completed = e.isFinished && !abandon)
    }

    fun stop() {
        running = false
        thread?.let { t -> runCatching { t.join(500) } }
        thread = null
    }

    // ------------------------------------------------------------ clock

    private fun clockAt(uptimeMs: Long): Double = when (phase) {
        Phase.PREROLL -> preRollStart + (uptimeMs - phaseStartUptime) / 1000.0
        Phase.PLAYING, Phase.FINISHED -> player?.songTimeAt(uptimeMs) ?: 0.0
        Phase.PAUSED, Phase.COUNTDOWN -> pausedSongTime
        Phase.IDLE -> 0.0
    }

    /** Song time used for judging and drawing: the clock shifted by the global offset. */
    private fun judgeTimeAt(uptimeMs: Long) = clockAt(uptimeMs) - config.offsetSeconds

    // ------------------------------------------------------------ input

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_POINTER_DOWN) return true
        val idx = event.actionIndex
        val lane = ((event.getX(idx) / w) * 4).toInt().coerceIn(0, 3)
        val eventTime = event.eventTime
        synchronized(lock) {
            val e = engine ?: return true
            if (phase != Phase.PLAYING && phase != Phase.PREROLL) return true
            val t = judgeTimeAt(eventTime)
            val ev = e.tap(lane, t)
            val now = SystemClock.uptimeMillis()
            laneFlashUptime[lane] = now
            if (ev.judgment != Judgment.BOO) {
                laneHitUptime[lane] = now
                laneHitJudgment[lane] = ev.judgment
                hitSounds?.play()
            }
            showJudgment(ev.judgment, ev.errorMs, now)
        }
        return true
    }

    private fun showJudgment(j: Judgment, errorMs: Double, now: Long) {
        lastJudgment = j
        lastJudgmentUptime = now
        lastErrorMs = errorMs
    }

    // ------------------------------------------------------------ surface / thread

    override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true; startThread() }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        w = max(1, width).toFloat(); h = max(1, height).toFloat()
        computeGeometry()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        stop()
    }

    private fun startThread() {
        if (!surfaceReady || running || engine == null) return
        running = true
        thread = Thread({ loop() }, "GameRender").also { it.priority = Thread.MAX_PRIORITY; it.start() }
    }

    private fun loop() {
        while (running) {
            tick()
            val canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) holder.lockHardwareCanvas() else holder.lockCanvas()
            if (canvas == null) { SystemClock.sleep(4); continue }
            try { render(canvas) } finally { runCatching { holder.unlockCanvasAndPost(canvas) } }
        }
    }

    private fun tick() {
        val now = SystemClock.uptimeMillis()
        var finished: GameResult? = null
        synchronized(lock) {
            val e = engine ?: return
            val p = player ?: return
            when (phase) {
                Phase.PREROLL -> if (clockAt(now) >= 0.0) { p.start(); phase = Phase.PLAYING }
                Phase.COUNTDOWN -> if (now >= countdownEndUptime) {
                    if (pausedSongTime < 0.0) {
                        preRollStart = pausedSongTime; phaseStartUptime = now; phase = Phase.PREROLL
                    } else {
                        p.start(); phase = Phase.PLAYING
                    }
                }
                else -> {}
            }
            if (phase == Phase.PLAYING) p.sync()
            if (phase == Phase.PLAYING || phase == Phase.PREROLL) {
                val t = judgeTimeAt(now)
                for (ev in e.update(t)) showJudgment(Judgment.MISS, 0.0, now)
                val songOver = p.completed && phase == Phase.PLAYING
                if (songOver && !e.isFinished) { e.abandon() }
                if (e.isFinished && (t >= lastNoteTime + END_DELAY_SECONDS || songOver)) {
                    phase = Phase.FINISHED
                    if (!finishNotified) { finishNotified = true; finished = e.result(completed = true) }
                }
            }
            if (e.score != cachedScore) { cachedScore = e.score; scoreText = String.format("%,d", e.score) }
            if (e.rawScore != cachedRaw) { cachedRaw = e.rawScore; rawText = String.format("%,d", e.rawScore) }
        }
        finished?.let { r -> post { listener?.onFinished(r) } }
    }

    // ------------------------------------------------------------ geometry

    private fun computeGeometry() {
        val totalLaneWidth = w * config.laneWidthPercent / 100f
        laneWidth = totalLaneWidth / 4f
        lanesLeft = (w - totalLaneWidth) / 2f
        arrowSize = laneWidth * 0.82f
        dirSign = if (config.scrollUp) 1f else -1f
        receptorY = if (config.scrollUp) h * RECEPTOR_FRACTION else h * (1f - RECEPTOR_FRACTION)
        pixelsPerSecond = config.scrollSpeed * h * BASE_SPEED_FRACTION
        for (lane in 0..3) {
            laneCenterX[lane] = lanesLeft + laneWidth * (lane + 0.5f)
            val rotation = when (lane) { 0 -> -90f; 1 -> 180f; 2 -> 0f; else -> 90f }
            matrix.reset()
            matrix.postRotate(rotation)
            matrix.postScale(arrowSize, arrowSize)
            arrowPaths[lane].reset()
            unitArrow.transform(matrix, arrowPaths[lane])
        }
        receptorPaint.strokeWidth = max(2f, arrowSize * 0.07f)
        noteOutlinePaint.strokeWidth = max(1.5f, arrowSize * 0.05f)
        glowPaint.strokeWidth = max(2f, arrowSize * 0.08f)
    }

    // ------------------------------------------------------------ drawing

    private fun render(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val e = engine
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        if (e == null) return
        val t = judgeTimeAt(now)

        // Lane shading and touch-column dividers.
        for (lane in 0..3) {
            if (lane % 2 == 0) canvas.drawRect(lanesLeft + lane * laneWidth, 0f, lanesLeft + (lane + 1) * laneWidth, h, lanePaint)
        }
        for (i in 1..3) canvas.drawLine(w * i / 4f, 0f, w * i / 4f, h, dividerPaint)

        drawReceptors(canvas, now)
        drawNotes(canvas, e, t)
        drawJudgment(canvas, e, now)
        drawHud(canvas, e, t)

        when (phase) {
            Phase.COUNTDOWN -> drawCountdown(canvas, now)
            Phase.PAUSED -> canvas.drawRect(0f, 0f, w, h, overlayPaint)
            else -> {}
        }
    }

    private fun drawReceptors(canvas: Canvas, now: Long) {
        for (lane in 0..3) {
            val cx = laneCenterX[lane]
            canvas.save()
            canvas.translate(cx, receptorY)
            val flashAge = now - laneFlashUptime[lane]
            if (flashAge in 0 until FLASH_MS) {
                val a = (1f - flashAge / FLASH_MS.toFloat())
                receptorFillPaint.color = Color.argb((160 * a).toInt(), 255, 255, 255)
                canvas.drawPath(arrowPaths[lane], receptorFillPaint)
            }
            canvas.drawPath(arrowPaths[lane], receptorPaint)
            val hitAge = now - laneHitUptime[lane]
            val hj = laneHitJudgment[lane]
            if (hj != null && hitAge in 0 until GLOW_MS) {
                val p = hitAge / GLOW_MS.toFloat()
                glowPaint.color = judgeColors[hj.ordinal]
                glowPaint.alpha = (255 * (1f - p)).toInt()
                val r = arrowSize * (0.55f + 0.5f * p)
                canvas.drawCircle(0f, 0f, r, glowPaint)
            }
            canvas.restore()
        }
    }

    private fun drawNotes(canvas: Canvas, e: GameEngine, t: Double) {
        val states = e.states
        if (states.isEmpty()) return
        val pastEdge = if (config.scrollUp) receptorY else h - receptorY
        val futureEdge = if (config.scrollUp) h - receptorY else receptorY
        val pastSeconds = pastEdge / pixelsPerSecond + 0.15
        val futureSeconds = futureEdge / pixelsPerSecond + 0.15
        val minTime = t - pastSeconds
        while (drawCursor < states.size && states[drawCursor].note.time < minTime) drawCursor++
        val first = drawCursor
        // Draw later notes first so the nearest note ends up on top.
        var lastIndex = first
        while (lastIndex < states.size && states[lastIndex].note.time <= t + futureSeconds) lastIndex++
        var k = lastIndex - 1
        while (k >= first) {
            val st = states[k]
            k--
            val j = st.judgment
            if (j != null && j != Judgment.MISS) continue // hit notes disappear
            val note = st.note
            val y = receptorY + dirSign * ((note.time - t) * pixelsPerSecond).toFloat()
            if (y < -arrowSize || y > h + arrowSize) continue
            val lane = note.lane
            canvas.save()
            canvas.translate(laneCenterX[lane], y)
            notePaint.color = if (config.snapColors) snapColors[note.snap.ordinal] else snapColors[Snap.QUARTER.ordinal]
            if (j == Judgment.MISS) notePaint.alpha = 90
            canvas.drawPath(arrowPaths[lane], notePaint)
            if (j == null) {
                canvas.drawPath(arrowPaths[lane], noteOutlinePaint)
                canvas.save()
                canvas.scale(0.45f, 0.45f)
                canvas.drawPath(arrowPaths[lane], noteHighlightPaint)
                canvas.restore()
            }
            canvas.restore()
        }
    }

    private fun drawJudgment(canvas: Canvas, e: GameEngine, now: Long) {
        val j = lastJudgment ?: return
        val age = now - lastJudgmentUptime
        if (age > JUDGMENT_MS) return
        val centerY = if (config.scrollUp) h * 0.46f else h * 0.40f
        val pop = if (age < 110) 1.25f - 0.25f * (age / 110f) else 1f
        val alpha = if (age > JUDGMENT_MS - 200) ((JUDGMENT_MS - age) / 200f).coerceIn(0f, 1f) else 1f
        textPaint.color = judgeColors[j.ordinal]
        textPaint.alpha = (255 * alpha).toInt()
        textPaint.textSize = h * 0.042f * pop
        canvas.drawText(j.label.uppercase(), w / 2f, centerY, textPaint)
        if (config.showEarlyLate && j != Judgment.MISS && j != Judgment.BOO && j != Judgment.AMAZING) {
            textPaint.textSize = h * 0.018f
            textPaint.color = if (lastErrorMs < 0) 0xFF6FB8FF.toInt() else 0xFFFF9A6F.toInt()
            textPaint.alpha = (255 * alpha).toInt()
            canvas.drawText(if (lastErrorMs < 0) "early" else "late", w / 2f, centerY + h * 0.028f, textPaint)
        }
        val combo = e.combo
        if (combo >= 2) {
            textPaint.color = 0xFFF2F4FF.toInt()
            textPaint.alpha = (255 * alpha).toInt()
            textPaint.textSize = h * 0.06f * (if (age < 110) 1.12f - 0.12f * (age / 110f) else 1f)
            canvas.drawText(combo.toString(), w / 2f, centerY + h * 0.095f, textPaint)
            textPaint.textSize = h * 0.018f
            textPaint.color = 0xFF8A93B5.toInt()
            textPaint.alpha = (255 * alpha).toInt()
            canvas.drawText("COMBO", w / 2f, centerY + h * 0.118f, textPaint)
        }
    }

    private fun drawHud(canvas: Canvas, e: GameEngine, t: Double) {
        val top = h * 0.045f
        hudPaint.textAlign = Paint.Align.LEFT
        hudPaint.textSize = h * 0.024f
        canvas.drawText(scoreText, w * 0.04f, top + h * 0.024f, hudPaint)
        hudDimPaint.textAlign = Paint.Align.LEFT
        hudDimPaint.textSize = h * 0.015f
        canvas.drawText("RAW $rawText", w * 0.04f, top + h * 0.044f, hudDimPaint)

        hudDimPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("${e.judgedNotes} / ${e.totalNotes}", w * 0.96f, top + h * 0.024f, hudDimPaint)
        canvas.drawText(songTitle, w * 0.96f, top + h * 0.044f, hudDimPaint)

        // Progress bar along the very top.
        val total = max(lastNoteTime, 0.001)
        val progress = (t / total).coerceIn(0.0, 1.0).toFloat()
        tmpRect.set(0f, 0f, w * progress, max(2f, h * 0.004f))
        canvas.drawRect(tmpRect, progressPaint)
    }

    private fun drawCountdown(canvas: Canvas, now: Long) {
        val remaining = (countdownEndUptime - now).coerceAtLeast(0)
        val n = (remaining / (COUNTDOWN_MS / 3) + 1).coerceIn(1, 3)
        textPaint.color = 0xFFFFD040.toInt()
        textPaint.alpha = 255
        textPaint.textSize = h * 0.12f
        canvas.drawText(n.toString(), w / 2f, h * 0.5f, textPaint)
    }

    private companion object {
        const val PREROLL_SECONDS = 2.0
        const val END_DELAY_SECONDS = 1.2
        const val COUNTDOWN_MS = 1500L
        const val FLASH_MS = 120L
        const val GLOW_MS = 220L
        const val JUDGMENT_MS = 700L
        const val RECEPTOR_FRACTION = 0.17f
        /** At scroll speed 1.0 an arrow moves this fraction of the screen height per second. */
        const val BASE_SPEED_FRACTION = 0.5f
    }
}
