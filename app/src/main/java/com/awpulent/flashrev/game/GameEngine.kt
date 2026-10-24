package com.awpulent.flashrev.game

import com.awpulent.flashrev.chart.Chart
import com.awpulent.flashrev.chart.Note

/**
 * Pure gameplay logic: takes taps and the current song time, produces judgments,
 * combo and score. Contains no Android dependencies so it can be unit tested.
 *
 * Thread-safety: all methods must be called from a single thread (the game loop).
 */
class GameEngine(val chart: Chart) {

    /** Per-note mutable state, parallel to [Chart.notes]. */
    class NoteState(val note: Note, val index: Int) {
        var judgment: Judgment? = null
        /** Timing error in ms (positive = late). Only meaningful when judged by a tap. */
        var errorMs: Double = 0.0
        val isJudged: Boolean get() = judgment != null
    }

    /** Outcome of a tap or of a note being missed. */
    class Event(val judgment: Judgment, val lane: Int, val note: NoteState?, val errorMs: Double, val combo: Int)

    val states: Array<NoteState> = Array(chart.notes.size) { NoteState(chart.notes[it], it) }

    private val laneNotes: Array<IntArray> = Array(4) { lane ->
        states.filter { it.note.lane == lane }.map { it.index }.toIntArray()
    }
    private val laneCursor = IntArray(4)

    private val counts = IntArray(Judgment.entries.size)
    var combo: Int = 0; private set
    var maxCombo: Int = 0; private set
    var rawScore: Int = 0; private set
    var score: Int = 0; private set
    var judgedNotes: Int = 0; private set

    val totalNotes: Int get() = states.size
    val isFinished: Boolean get() = judgedNotes >= states.size

    fun count(j: Judgment): Int = counts[j.ordinal]

    /** Sum of absolute timing errors of tapped notes, for the mean error display. */
    private var errorSum = 0.0
    private var errorCount = 0
    val meanErrorMs: Double get() = if (errorCount == 0) 0.0 else errorSum / errorCount

    /**
     * Registers a tap on [lane] at [songTime] seconds.
     * The earliest unjudged note in the lane within the Average window is hit; otherwise it's a Boo.
     */
    fun tap(lane: Int, songTime: Double): Event {
        require(lane in 0..3)
        val window = Judgment.MAX_WINDOW_MS / 1000.0
        val indices = laneNotes[lane]
        // Anything the player can no longer hit is a miss, regardless of frame timing.
        while (laneCursor[lane] < indices.size) {
            val st = states[indices[laneCursor[lane]]]
            if (st.isJudged) { laneCursor[lane]++; continue }
            if (st.note.time < songTime - window) { applyMiss(st); laneCursor[lane]++; continue }
            break
        }
        if (laneCursor[lane] < indices.size) {
            val st = states[indices[laneCursor[lane]]]
            val errorMs = (songTime - st.note.time) * 1000.0
            if (kotlin.math.abs(errorMs) <= Judgment.MAX_WINDOW_MS) {
                val j = Judgment.forError(errorMs)
                apply(st, j, errorMs)
                laneCursor[lane]++
                return Event(j, lane, st, errorMs, combo)
            }
        }
        applyJudgment(Judgment.BOO)
        return Event(Judgment.BOO, lane, null, 0.0, combo)
    }

    /**
     * Advances time: every unjudged note older than the Average window becomes a Miss.
     * Returns the miss events generated (usually none).
     */
    fun update(songTime: Double): List<Event> {
        val window = Judgment.MAX_WINDOW_MS / 1000.0
        var events: ArrayList<Event>? = null
        for (lane in 0..3) {
            val indices = laneNotes[lane]
            while (laneCursor[lane] < indices.size) {
                val st = states[indices[laneCursor[lane]]]
                if (st.isJudged) { laneCursor[lane]++; continue }
                if (st.note.time < songTime - window) {
                    applyMiss(st)
                    laneCursor[lane]++
                    (events ?: ArrayList<Event>().also { events = it }).add(Event(Judgment.MISS, lane, st, 0.0, combo))
                    continue
                }
                break
            }
        }
        return events ?: emptyList()
    }

    /** Marks every remaining note as missed (used when quitting early). */
    fun abandon() {
        for (st in states) if (!st.isJudged) applyMiss(st)
    }

    private fun applyMiss(st: NoteState) = apply(st, Judgment.MISS, 0.0)

    private fun apply(st: NoteState, j: Judgment, errorMs: Double) {
        st.judgment = j
        st.errorMs = errorMs
        judgedNotes++
        if (j != Judgment.MISS) { errorSum += kotlin.math.abs(errorMs); errorCount++ }
        applyJudgment(j)
    }

    private fun applyJudgment(j: Judgment) {
        counts[j.ordinal]++
        rawScore += j.rawPoints
        if (j.breaksCombo) combo = 0
        if (j.extendsCombo) {
            combo++
            if (combo > maxCombo) maxCombo = combo
            // FFR-style running score: judgment points plus the current combo as a bonus.
            score += j.rawPoints + combo
        } else {
            score += j.rawPoints
        }
    }

    fun result(completed: Boolean): GameResult = GameResult(
        amazing = count(Judgment.AMAZING),
        perfect = count(Judgment.PERFECT),
        good = count(Judgment.GOOD),
        average = count(Judgment.AVERAGE),
        miss = count(Judgment.MISS),
        boo = count(Judgment.BOO),
        rawScore = rawScore,
        score = score,
        maxCombo = maxCombo,
        totalNotes = totalNotes,
        completed = completed,
    )
}
