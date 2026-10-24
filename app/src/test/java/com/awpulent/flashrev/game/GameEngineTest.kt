package com.awpulent.flashrev.game

import com.awpulent.flashrev.chart.Chart
import com.awpulent.flashrev.chart.Difficulty
import com.awpulent.flashrev.chart.Note
import com.awpulent.flashrev.chart.Snap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameEngineTest {

    private fun chart(vararg notes: Pair<Double, Int>) = Chart(
        Difficulty.MEDIUM, 5, "",
        notes.map { (t, lane) -> Note(t, lane, t * 2, Snap.QUARTER) }.sorted(),
    )

    @Test
    fun judgmentWindowsMatchFfr() {
        assertEquals(Judgment.AMAZING, Judgment.forError(0.0))
        assertEquals(Judgment.AMAZING, Judgment.forError(-17.0))
        assertEquals(Judgment.PERFECT, Judgment.forError(17.5))
        assertEquals(Judgment.PERFECT, Judgment.forError(50.0))
        assertEquals(Judgment.GOOD, Judgment.forError(-51.0))
        assertEquals(Judgment.GOOD, Judgment.forError(83.0))
        assertEquals(Judgment.AVERAGE, Judgment.forError(84.0))
        assertEquals(Judgment.AVERAGE, Judgment.forError(117.0))
        assertEquals(Judgment.MISS, Judgment.forError(118.0))
    }

    @Test
    fun tapHitsNearestUpcomingNoteAndScores() {
        val e = GameEngine(chart(1.0 to 0, 2.0 to 0))
        val ev = e.tap(0, 1.010)
        assertEquals(Judgment.AMAZING, ev.judgment)
        assertEquals(10.0, ev.errorMs, 1e-6)
        assertEquals(1, e.combo)
        assertEquals(50, e.rawScore)
        assertEquals(51, e.score) // 50 points + combo bonus of 1
        val ev2 = e.tap(0, 2.060)
        assertEquals(Judgment.GOOD, ev2.judgment)
        assertEquals(2, e.combo)
        assertEquals(75, e.rawScore)
        assertEquals(51 + 25 + 2, e.score)
        assertTrue(e.isFinished)
    }

    @Test
    fun tapWithNoNoteInRangeIsBooAndKeepsCombo() {
        val e = GameEngine(chart(1.0 to 0, 5.0 to 1))
        e.tap(0, 1.0)
        assertEquals(1, e.combo)
        val ev = e.tap(1, 1.0) // nothing near in lane 1
        assertEquals(Judgment.BOO, ev.judgment)
        assertNull(ev.note)
        assertEquals(1, e.combo)
        assertEquals(45, e.rawScore)
        assertEquals(1, e.count(Judgment.BOO))
        assertFalse(e.isFinished)
    }

    @Test
    fun earlyTapOutsideWindowIsBooAndNoteStaysHittable() {
        val e = GameEngine(chart(1.0 to 2))
        assertEquals(Judgment.BOO, e.tap(2, 0.80).judgment)
        assertEquals(Judgment.PERFECT, e.tap(2, 0.97).judgment)
    }

    @Test
    fun notesScrollingPastBecomeMissesAndBreakCombo() {
        val e = GameEngine(chart(1.0 to 0, 1.5 to 1, 3.0 to 2))
        e.tap(0, 1.0)
        assertTrue(e.update(1.2).isEmpty())
        val misses = e.update(1.7)   // 1.5 + 0.117 < 1.7
        assertEquals(1, misses.size)
        assertEquals(Judgment.MISS, misses[0].judgment)
        assertEquals(1, misses[0].lane)
        assertEquals(0, e.combo)
        assertEquals(1, e.maxCombo)
        assertEquals(40, e.rawScore)
        assertEquals(2, e.judgedNotes)
    }

    @Test
    fun earliestNoteInLaneIsHitFirst() {
        // Two notes 60 ms apart in the same lane; a tap between them hits the earlier one.
        val e = GameEngine(chart(1.00 to 3, 1.06 to 3))
        val ev = e.tap(3, 1.04)
        assertEquals(0, ev.note!!.index)
        assertEquals(Judgment.PERFECT, ev.judgment)
        val ev2 = e.tap(3, 1.07)
        assertEquals(1, ev2.note!!.index)
        assertEquals(Judgment.AMAZING, ev2.judgment)
    }

    @Test
    fun lateTapAfterWindowMissesOldNoteThenBoos() {
        val e = GameEngine(chart(1.0 to 0))
        val ev = e.tap(0, 1.2)
        assertEquals(Judgment.BOO, ev.judgment)
        assertEquals(1, e.count(Judgment.MISS))
        assertTrue(e.isFinished)
    }

    @Test
    fun abandonMarksRemainingAsMissed() {
        val e = GameEngine(chart(1.0 to 0, 2.0 to 1, 3.0 to 2))
        e.tap(0, 1.0)
        e.abandon()
        val r = e.result(completed = false)
        assertEquals(1, r.amazing)
        assertEquals(2, r.miss)
        assertFalse(r.completed)
        assertEquals(GameResult.Rank.CLEAR, r.rank)
    }

    @Test
    fun ranksAndEncoding() {
        val aaa = GameResult(10, 5, 0, 0, 0, 0, 750, 800, 15, 15, true)
        assertEquals(GameResult.Rank.AAA, aaa.rank)
        assertEquals(1.0, aaa.accuracy, 1e-9)
        assertEquals(GameResult.Rank.SDG, aaa.copy(good = 9).rank)
        assertEquals(GameResult.Rank.FC, aaa.copy(good = 10).rank)
        assertEquals(GameResult.Rank.FC, aaa.copy(average = 1).rank)
        assertEquals(GameResult.Rank.BLACKFLAG, aaa.copy(boo = 1).rank)
        assertEquals(GameResult.Rank.CLEAR, aaa.copy(miss = 1).rank)
        assertEquals(aaa, GameResult.decode(aaa.encode()))
        assertNull(GameResult.decode("1,2,3"))
    }
}
