package com.awpulent.flashrev.chart

import org.junit.Assert.assertEquals
import org.junit.Test

class TimingDataTest {
    @Test
    fun constantBpm() {
        val t = TimingData(0.0, listOf(0.0 to 120.0), emptyList())
        assertEquals(0.0, t.beatToTime(0.0), 1e-9)
        assertEquals(0.5, t.beatToTime(1.0), 1e-9)
        assertEquals(2.0, t.beatToTime(4.0), 1e-9)
    }

    @Test
    fun bpmChanges() {
        val t = TimingData(0.0, listOf(0.0 to 60.0, 2.0 to 120.0), emptyList())
        assertEquals(2.0, t.beatToTime(2.0), 1e-9)
        assertEquals(2.5, t.beatToTime(3.0), 1e-9)
    }

    @Test
    fun stopsAndOffset() {
        val t = TimingData(0.25, listOf(0.0 to 120.0), listOf(1.0 to 0.5))
        assertEquals(-0.25, t.beatToTime(0.0), 1e-9)
        assertEquals(0.25, t.beatToTime(1.0), 1e-9)          // stop applies strictly after its beat
        assertEquals(0.25 + 0.5 + 0.5, t.beatToTime(2.0), 1e-9)
    }

    @Test
    fun parsePairsIgnoresGarbage() {
        val pairs = TimingData.parsePairs("0=120, 4.5=180.25 ,, x=1, 8")
        assertEquals(listOf(0.0 to 120.0, 4.5 to 180.25), pairs)
    }
}
