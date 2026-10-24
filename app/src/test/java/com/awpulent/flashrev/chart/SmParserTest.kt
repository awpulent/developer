package com.awpulent.flashrev.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmParserTest {

    private val sm = """
        #TITLE:Test Song;
        #SUBTITLE:;
        #ARTIST:Someone;
        #MUSIC:song.ogg;
        #OFFSET:-0.5;   // beat 0 is half a second into the audio
        #SAMPLESTART:12.5;
        #BPMS:0.000=120.000,8.000=240.000;
        #STOPS:4.000=1.000;
        #NOTES:
             dance-single:
             author:
             Hard:
             9:
             0,0,0,0,0:
        1000
        0100
        0010
        0001
        ,
        2000
        0000
        3000
        M000
        ,
        1100
        0000
        0000
        0000
        0000
        0000
        0000
        0000
        0000
        0000
        0000
        0000
        ;
        #NOTES:
             dance-double:
             :
             Hard:
             9:
             0,0,0,0,0:
        10000000
        ;
    """.trimIndent()

    @Test
    fun parsesHeaderAndOnlySingleCharts() {
        val parsed = SmParser.parse(sm)
        assertEquals("Test Song", parsed.title)
        assertEquals("Someone", parsed.artist)
        assertEquals("song.ogg", parsed.music)
        assertEquals(12.5, parsed.sampleStart, 1e-9)
        assertEquals("120–240", parsed.displayBpm)
        assertEquals(1, parsed.charts.size)
        val chart = parsed.charts[0]
        assertEquals(Difficulty.HARD, chart.difficulty)
        assertEquals(9, chart.meter)
        assertEquals("author", chart.description)
    }

    @Test
    fun noteTimesLanesAndSnaps() {
        val chart = SmParser.parse(sm).charts[0]
        // Measure 1: 4 taps on quarter notes, lanes 0..3. Measure 2: a hold head (tap) and a mine (dropped).
        // Measure 3: a jump on beat 8 (12 rows -> 12ths).
        assertEquals(4 + 1 + 2, chart.notes.size)
        val n = chart.notes
        assertEquals(0.5, n[0].time, 1e-9)                 // beat 0 at -offset
        assertEquals(0, n[0].lane)
        assertEquals(Snap.QUARTER, n[0].snap)
        assertEquals(0.5 + 1.5, n[3].time, 1e-9)           // beat 3 at 120 bpm
        assertEquals(3, n[3].lane)
        assertEquals(0.5 + 2.0, n[4].time, 1e-9)           // hold head, beat 4 (stop at beat 4 does not delay beat 4 itself)
        assertEquals(0, n[4].lane)
        // Beat 8: 8 beats at 120 bpm = 4 s, plus the 1 s stop => 5.0 s, plus 0.5 offset.
        assertEquals(5.5, n[5].time, 1e-9)
        assertEquals(5.5, n[6].time, 1e-9)
        assertEquals(listOf(0, 1), listOf(n[5].lane, n[6].lane))
        assertEquals(Snap.QUARTER, n[5].snap)
    }

    @Test
    fun parsesSscNoteDataBlocks() {
        val ssc = """
            #VERSION:0.83;
            #TITLE:SSC Song;
            #OFFSET:0.0;
            #BPMS:0=150;
            #NOTEDATA:;
            #STEPSTYPE:dance-single;
            #DIFFICULTY:Challenge;
            #METER:12;
            #NOTES:
            0000
            0000
            0000
            0000
            0000
            0000
            0000
            1000
            ;
            #NOTEDATA:;
            #STEPSTYPE:dance-single;
            #DIFFICULTY:Easy;
            #METER:2;
            #BPMS:0=300;
            #NOTES:
            0001
            ;
        """.trimIndent()
        val parsed = SmParser.parse(ssc)
        assertEquals("SSC Song", parsed.title)
        assertEquals(2, parsed.charts.size)
        val easy = parsed.charts[0]
        val challenge = parsed.charts[1]
        assertEquals(Difficulty.EASY, easy.difficulty)
        assertEquals(Difficulty.CHALLENGE, challenge.difficulty)
        // Row 7 of 8 -> beat 3.5 at 150 bpm = 1.4 s, an 8th note.
        assertEquals(1.4, challenge.notes[0].time, 1e-9)
        assertEquals(Snap.EIGHTH, challenge.notes[0].snap)
        assertEquals(0.0, easy.notes[0].time, 1e-9)
        assertEquals(3, easy.notes[0].lane)
    }

    @Test
    fun snapClassification() {
        assertEquals(Snap.QUARTER, Snap.fromRow192(0))
        assertEquals(Snap.EIGHTH, Snap.fromRow192(24))
        assertEquals(Snap.TWELFTH, Snap.fromRow192(16))
        assertEquals(Snap.SIXTEENTH, Snap.fromRow192(12))
        assertEquals(Snap.TWENTY_FOURTH, Snap.fromRow192(8))
        assertEquals(Snap.THIRTY_SECOND, Snap.fromRow192(6))
        assertEquals(Snap.FORTY_EIGHTH, Snap.fromRow192(4))
        assertEquals(Snap.SIXTY_FOURTH, Snap.fromRow192(3))
        assertEquals(Snap.OTHER, Snap.fromRow192(1))
    }

    @Test
    fun chartsWithoutNotesAreDropped() {
        val text = "#TITLE:x;\n#BPMS:0=120;\n#NOTES:\ndance-single:\n:\nEasy:\n1:\n:\n0000\n;"
        assertTrue(SmParser.parse(text).charts.isEmpty())
    }
}
