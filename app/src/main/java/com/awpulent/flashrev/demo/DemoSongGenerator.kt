package com.awpulent.flashrev.demo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedurally generates the built-in demo song "Neon Circuit": a ~57 second chiptune
 * track plus three step charts, so the game is playable without shipping any audio.
 *
 * The audio and the charts are derived from the same event list, so the steps always
 * land on real musical onsets. Everything is deterministic (seeded) and pure Kotlin.
 */
object DemoSongGenerator {
    /** Bump when the generator changes so installed copies get regenerated. */
    const val VERSION = 1

    const val SAMPLE_RATE = 44100
    const val BPM = 140.0
    const val MEASURES = 32
    const val SLOTS = 16                         // 16th-note grid
    const val LEAD_IN_SECONDS = 1.0              // beat 0 happens here
    const val TITLE = "Neon Circuit"
    const val ARTIST = "FlashRev"
    const val AUDIO_FILE = "demo.wav"
    const val STEP_FILE = "demo.sm"

    private const val SEC_PER_SLOT = 60.0 / BPM / 4.0
    private const val TAIL_SECONDS = 1.5

    private enum class Section { INTRO, A, B, A2, OUTRO }

    private fun sectionOf(measure: Int) = when (measure) {
        in 0..3 -> Section.INTRO
        in 4..11 -> Section.A
        in 12..19 -> Section.B
        in 20..27 -> Section.A2
        else -> Section.OUTRO
    }

    private enum class DrumKind { KICK, SNARE, HAT, OPEN_HAT }

    class MelodyNote(val measure: Int, val slot: Int, val degree: Int, val midi: Int, val lengthSlots: Int)
    private class Drum(val measure: Int, val slot: Int, val kind: DrumKind, val velocity: Double)
    private class BassNote(val measure: Int, val slot: Int, val midi: Int, val lengthSlots: Int)
    private class Pad(val measure: Int, val midis: IntArray)

    class Generated(
        val pcm: ShortArray,
        val smText: String,
        val melody: List<MelodyNote>,
        /** Lane sets per difficulty name ("Easy", "Medium", "Hard"): slot index (measure*16+slot) -> lanes. */
        val charts: Map<String, Map<Int, Set<Int>>>,
    )

    // A minor pentatonic ladder: degree index -> midi.
    private val PENTA = intArrayOf(0, 3, 5, 7, 10)
    private const val LEAD_BASE = 57 // A3
    private fun degreeToMidi(degree: Int) = LEAD_BASE + 12 * (degree / 5) + PENTA[degree % 5]
    private const val DEGREE_MAX = 9

    /** Chord roots (pitch class relative to A = 0) per measure, with the pentatonic chord tones. */
    private class Chord(val rootMidi: Int, val toneClasses: IntArray)
    private val AM = Chord(45, intArrayOf(0, 3, 7))     // A C E
    private val F = Chord(41, intArrayOf(8, 0, 3))      // F A C
    private val C = Chord(48, intArrayOf(3, 7, 10))     // C E G
    private val G = Chord(43, intArrayOf(10, 2, 5))     // G B D

    private fun chordFor(measure: Int): Chord {
        val i = measure % 8
        return when (sectionOf(measure)) {
            Section.INTRO -> if (measure < 2) AM else listOf(AM, F)[measure - 2]
            Section.A, Section.A2 -> listOf(AM, F, C, G, AM, F, C, G)[i]
            Section.B -> listOf(F, G, AM, C, F, G, AM, AM)[i]
            Section.OUTRO -> listOf(AM, F, AM, AM)[measure - 28]
        }
    }

    private val MASKS_A = listOf(
        intArrayOf(0, 4, 6, 8, 12),
        intArrayOf(0, 2, 4, 8, 10, 12, 14),
        intArrayOf(0, 4, 8, 10, 12),
        intArrayOf(0, 6, 8, 14),
        intArrayOf(0, 2, 4, 6, 8, 12),
        intArrayOf(0, 4, 8, 12, 14),
    )
    private val MASKS_B = listOf(
        intArrayOf(0, 2, 4, 6, 8, 10, 12, 14),
        intArrayOf(0, 3, 6, 8, 11, 14),
        intArrayOf(0, 2, 3, 4, 8, 10, 11, 12),
        intArrayOf(0, 4, 7, 8, 12, 14),
        intArrayOf(0, 2, 4, 8, 9, 10, 12, 14),
        intArrayOf(0, 2, 4, 6, 8, 12, 14, 15),
    )
    private val MASKS_OUTRO = listOf(intArrayOf(0, 8), intArrayOf(0, 6), intArrayOf(0), intArrayOf())

    fun generate(): Generated {
        val rng = Random(20260424)
        val melody = buildMelody(rng)
        val drums = buildDrums()
        val bass = buildBass()
        val pads = buildPads()
        val pcm = render(melody, drums, bass, pads)
        val charts = buildCharts(melody, drums, rng)
        return Generated(pcm, buildSm(charts), melody, charts)
    }

    // ---------------------------------------------------------------- composition

    private fun buildMelody(rng: Random): List<MelodyNote> {
        // Collect onsets first so note lengths can run up to the next onset.
        val onsets = ArrayList<IntArray>() // [measure, slot]
        for (m in 0 until MEASURES) {
            val mask = when (sectionOf(m)) {
                Section.INTRO -> continue
                Section.A, Section.A2 -> MASKS_A[rng.nextInt(MASKS_A.size)]
                Section.B -> MASKS_B[rng.nextInt(MASKS_B.size)]
                Section.OUTRO -> MASKS_OUTRO[m - 28]
            }
            for (s in mask) onsets.add(intArrayOf(m, s))
        }
        val notes = ArrayList<MelodyNote>()
        var degree = 2 // D4
        for ((i, on) in onsets.withIndex()) {
            val (m, s) = on
            val chord = chordFor(m)
            val lo = if (sectionOf(m) == Section.B) 2 else 0
            val nextIndex = if (i + 1 < onsets.size) onsets[i + 1][0] * SLOTS + onsets[i + 1][1] else m * SLOTS + s + 4
            val gap = nextIndex - (m * SLOTS + s)
            degree = if (s == 0) {
                nearestChordTone(degree, chord, lo)
            } else {
                var step: Int
                do {
                    step = STEPS[rng.nextInt(STEPS.size)]
                } while (gap <= 1 && step == 0) // no 16th-note jacks
                (degree + step).let { d -> if (d < lo) lo + (lo - d) else if (d > DEGREE_MAX) DEGREE_MAX - (d - DEGREE_MAX) else d }
                    .coerceIn(lo, DEGREE_MAX)
            }
            val length = (gap - 1).coerceIn(1, 4)
            notes.add(MelodyNote(m, s, degree, degreeToMidi(degree), length))
        }
        return notes
    }

    private val STEPS = intArrayOf(-2, -1, -1, 0, 1, 1, 2)

    private fun nearestChordTone(degree: Int, chord: Chord, lo: Int): Int {
        var best = degree
        var bestDist = Int.MAX_VALUE
        for (d in lo..DEGREE_MAX) {
            val pc = (degreeToMidi(d) - 57) % 12
            if (pc in chord.toneClasses) {
                val dist = abs(d - degree)
                if (dist < bestDist) { bestDist = dist; best = d }
            }
        }
        return best
    }

    private fun buildDrums(): List<Drum> {
        val d = ArrayList<Drum>()
        fun add(m: Int, s: Int, k: DrumKind, v: Double = 1.0) = d.add(Drum(m, s, k, v))
        for (m in 0 until MEASURES) {
            when (sectionOf(m)) {
                Section.INTRO -> {
                    add(m, 0, DrumKind.KICK); add(m, 8, DrumKind.KICK)
                    if (m >= 1) for (s in 0 until SLOTS step 2) add(m, s, DrumKind.HAT, 0.8)
                    if (m >= 2) { add(m, 4, DrumKind.SNARE); add(m, 12, DrumKind.SNARE) }
                    if (m == 3) for (s in 12..15) add(m, s, DrumKind.SNARE, 0.6 + 0.1 * (s - 12))
                }
                Section.A, Section.A2 -> {
                    add(m, 0, DrumKind.KICK); add(m, 8, DrumKind.KICK)
                    if (sectionOf(m) == Section.A2) add(m, 6, DrumKind.KICK, 0.8) else if (m % 2 == 1) add(m, 10, DrumKind.KICK, 0.8)
                    add(m, 4, DrumKind.SNARE); add(m, 12, DrumKind.SNARE)
                    for (s in 0 until SLOTS step 2) add(m, s, DrumKind.HAT, 0.8)
                    if (m % 4 == 3) add(m, 14, DrumKind.OPEN_HAT, 0.8)
                }
                Section.B -> {
                    for (s in 0 until SLOTS step 4) add(m, s, DrumKind.KICK)
                    add(m, 4, DrumKind.SNARE); add(m, 12, DrumKind.SNARE)
                    for (s in 0 until SLOTS) add(m, s, DrumKind.HAT, if (s % 2 == 0) 0.8 else 0.35)
                    if (m % 2 == 1) add(m, 14, DrumKind.OPEN_HAT, 0.8)
                }
                Section.OUTRO -> {
                    add(m, 0, DrumKind.KICK)
                    if (m < 31) { add(m, 8, DrumKind.KICK, 0.8); add(m, 4, DrumKind.SNARE, 0.7); add(m, 12, DrumKind.SNARE, 0.7) }
                    if (m < 30) for (s in 0 until SLOTS step 2) add(m, s, DrumKind.HAT, 0.6)
                }
            }
        }
        return d
    }

    private fun buildBass(): List<BassNote> {
        val b = ArrayList<BassNote>()
        for (m in 0 until MEASURES) {
            val root = chordFor(m).rootMidi
            when (sectionOf(m)) {
                Section.INTRO -> if (m >= 2) for (s in 0 until SLOTS step 2) b.add(BassNote(m, s, root, 1))
                Section.A, Section.A2 -> for (s in 0 until SLOTS step 2) {
                    val midi = if (s == 14) root + 7 else root
                    b.add(BassNote(m, s, midi, 1))
                }
                Section.B -> for (s in 0 until SLOTS step 2) {
                    val midi = if (s == 6 || s == 14) root + 12 else root
                    b.add(BassNote(m, s, midi, 1))
                }
                Section.OUTRO -> if (m < 31) b.add(BassNote(m, 0, root, 8)) else b.add(BassNote(m, 0, root, 16))
            }
        }
        return b
    }

    private fun buildPads(): List<Pad> = (0 until MEASURES).map { m ->
        val c = chordFor(m)
        // Root an octave up plus the two other chord tones placed above it.
        val root = c.rootMidi + 12
        val tones = c.toneClasses.map { pc -> var n = root + ((pc - (root - 45) % 12 + 24) % 12); if (n < root) n += 12; n }
        Pad(m, tones.distinct().sorted().toIntArray())
    }

    // ---------------------------------------------------------------- charts

    private fun buildCharts(melody: List<MelodyNote>, drums: List<Drum>, rng: Random): Map<String, Map<Int, Set<Int>>> {
        fun key(m: Int, s: Int) = m * SLOTS + s
        val melodyAt = melody.associateBy { key(it.measure, it.slot) }
        val kickOrSnare = drums.filter { it.kind == DrumKind.KICK || it.kind == DrumKind.SNARE }
            .map { key(it.measure, it.slot) }.toSet()
        fun laneOf(n: MelodyNote) = n.degree % 4

        val easy = sortedMapOf<Int, MutableSet<Int>>()
        var prevLane = -1
        for (m in 4 until 28) for (s in 0 until SLOTS step 4) {
            val k = key(m, s)
            val mel = melodyAt[k]
            if (mel == null && k !in kickOrSnare) continue
            var lane = mel?.let { laneOf(it) } ?: (prevLane + 1 + rng.nextInt(3)) % 4
            if (lane == prevLane && rng.nextInt(3) != 0) lane = (lane + 1 + rng.nextInt(3)) % 4
            easy.getOrPut(k) { HashSet() }.add(lane)
            prevLane = lane
        }

        val medium = sortedMapOf<Int, MutableSet<Int>>()
        for (n in melody) if (n.slot % 2 == 0) medium.getOrPut(key(n.measure, n.slot)) { HashSet() }.add(laneOf(n))

        val hard = sortedMapOf<Int, MutableSet<Int>>()
        for (n in melody) {
            val k = key(n.measure, n.slot)
            val lane = laneOf(n)
            val lanes = hard.getOrPut(k) { HashSet() }
            lanes.add(lane)
            if (sectionOf(n.measure) == Section.B && n.slot == 0) lanes.add((lane + 2) % 4) // jumps on chorus downbeats
        }
        var lastHardLane = 0
        for (m in 20 until 28) for (s in intArrayOf(4, 12)) {
            val k = key(m, s)
            if (hard.containsKey(k)) continue
            val prev = hard.headMap(k).let { if (it.isEmpty()) null else it[it.lastKey()] }
            lastHardLane = prev?.first() ?: lastHardLane
            hard[k] = hashSetOf((lastHardLane + 2) % 4)
        }

        return linkedMapOf("Easy" to easy, "Medium" to medium, "Hard" to hard)
    }

    private fun buildSm(charts: Map<String, Map<Int, Set<Int>>>): String {
        val sb = StringBuilder()
        sb.append("#TITLE:").append(TITLE).append(";\n")
        sb.append("#SUBTITLE:(built-in demo);\n")
        sb.append("#ARTIST:").append(ARTIST).append(";\n")
        sb.append("#MUSIC:").append(AUDIO_FILE).append(";\n")
        sb.append("#OFFSET:").append(String.format(java.util.Locale.US, "%.3f", -LEAD_IN_SECONDS)).append(";\n")
        sb.append("#SAMPLESTART:").append(String.format(java.util.Locale.US, "%.3f", LEAD_IN_SECONDS + 12 * SLOTS * SEC_PER_SLOT)).append(";\n")
        sb.append("#SAMPLELENGTH:12.000;\n")
        sb.append("#DISPLAYBPM:140;\n")
        sb.append("#BPMS:0.000=").append(String.format(java.util.Locale.US, "%.3f", BPM)).append(";\n")
        sb.append("#STOPS:;\n\n")
        val meters = mapOf("Easy" to 3, "Medium" to 6, "Hard" to 9)
        for ((name, lanesBySlot) in charts) {
            sb.append("//---------------dance-single - ----------------\n")
            sb.append("#NOTES:\n     dance-single:\n     :\n     ").append(name).append(":\n     ")
                .append(meters[name] ?: 5).append(":\n     0,0,0,0,0:\n")
            for (m in 0 until MEASURES) {
                for (s in 0 until SLOTS) {
                    val lanes = lanesBySlot[m * SLOTS + s]
                    for (lane in 0..3) sb.append(if (lanes != null && lane in lanes) '1' else '0')
                    sb.append('\n')
                }
                sb.append(if (m == MEASURES - 1) ";\n\n" else ",\n")
            }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- synthesis

    private fun slotTime(measure: Int, slot: Int) = LEAD_IN_SECONDS + (measure * SLOTS + slot) * SEC_PER_SLOT
    private fun midiHz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)

    private fun render(melody: List<MelodyNote>, drums: List<Drum>, bass: List<BassNote>, pads: List<Pad>): ShortArray {
        val totalSeconds = LEAD_IN_SECONDS + MEASURES * SLOTS * SEC_PER_SLOT + TAIL_SECONDS
        val n = (totalSeconds * SAMPLE_RATE).toInt()
        val buf = FloatArray(n)
        val noise = Random(7)

        for (p in pads) renderPad(buf, slotTime(p.measure, 0), SLOTS * SEC_PER_SLOT, p.midis, sectionOf(p.measure))
        for (b in bass) renderBass(buf, slotTime(b.measure, b.slot), b.lengthSlots * SEC_PER_SLOT * 0.9, midiHz(b.midi))
        for (d in drums) when (d.kind) {
            DrumKind.KICK -> renderKick(buf, slotTime(d.measure, d.slot), d.velocity)
            DrumKind.SNARE -> renderSnare(buf, slotTime(d.measure, d.slot), d.velocity, noise)
            DrumKind.HAT -> renderHat(buf, slotTime(d.measure, d.slot), 0.045, d.velocity, noise)
            DrumKind.OPEN_HAT -> renderHat(buf, slotTime(d.measure, d.slot), 0.22, d.velocity, noise)
        }
        for (m in melody) renderLead(buf, slotTime(m.measure, m.slot), m.lengthSlots * SEC_PER_SLOT - 0.02, midiHz(m.midi))

        // Fade out over the last two measures.
        val fadeStart = slotTime(MEASURES - 2, 0)
        val fadeEnd = totalSeconds
        for (i in (fadeStart * SAMPLE_RATE).toInt() until n) {
            val t = i / SAMPLE_RATE.toDouble()
            buf[i] *= (1.0 - (t - fadeStart) / (fadeEnd - fadeStart)).coerceIn(0.0, 1.0).toFloat()
        }

        var peak = 1e-6f
        for (v in buf) if (abs(v) > peak) peak = abs(v)
        val gain = 0.89f / peak
        return ShortArray(n) { i -> (buf[i] * gain * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
    }

    private inline fun mix(buf: FloatArray, startSec: Double, durSec: Double, fn: (t: Double) -> Double) {
        val start = (startSec * SAMPLE_RATE).toInt()
        val end = minOf(buf.size, start + (durSec * SAMPLE_RATE).toInt())
        for (i in maxOf(0, start) until end) {
            buf[i] += fn((i - start) / SAMPLE_RATE.toDouble()).toFloat()
        }
    }

    private fun renderKick(buf: FloatArray, t0: Double, vel: Double) {
        var phase = 0.0
        val dt = 1.0 / SAMPLE_RATE
        mix(buf, t0, 0.28) { t ->
            val f = 45.0 + 130.0 * exp(-t * 28.0)
            phase += 2 * PI * f * dt
            sin(phase) * exp(-t * 11.0) * 0.95 * vel
        }
    }

    private fun renderSnare(buf: FloatArray, t0: Double, vel: Double, rng: Random) {
        var hp = 0.0
        mix(buf, t0, 0.22) { t ->
            val white = rng.nextDouble() * 2 - 1
            hp = 0.7 * hp + white - 0.7 * white // crude tilt; keeps noise bright
            val body = sin(2 * PI * 185.0 * t) * exp(-t * 32.0) * 0.45
            (white * exp(-t * 20.0) * 0.5 + body) * vel
        }
    }

    private fun renderHat(buf: FloatArray, t0: Double, dur: Double, vel: Double, rng: Random) {
        var prev = 0.0
        val decay = 4.5 / dur
        mix(buf, t0, dur) { t ->
            val white = rng.nextDouble() * 2 - 1
            val hp = white - prev // first-difference high-pass
            prev = white
            hp * exp(-t * decay) * 0.22 * vel
        }
    }

    private fun renderBass(buf: FloatArray, t0: Double, dur: Double, hz: Double) {
        var lp = 0.0
        mix(buf, t0, dur + 0.03) { t ->
            val env = minOf(1.0, t / 0.005) * (if (t > dur) exp(-(t - dur) * 120.0) else 1.0) * (0.6 + 0.4 * exp(-t * 6.0))
            val saw = 2.0 * ((t * hz) % 1.0) - 1.0
            val sub = sin(2 * PI * hz * 0.5 * t)
            lp += 0.18 * ((saw * 0.7 + sub * 0.5) - lp)
            lp * env * 0.42
        }
    }

    private fun renderLead(buf: FloatArray, t0: Double, dur: Double, hz: Double) {
        val d = maxOf(0.05, dur)
        mix(buf, t0, d + 0.06) { t ->
            val env = minOf(1.0, t / 0.008) * (if (t > d) exp(-(t - d) * 60.0) else 1.0)
            val vib = 1.0 + 0.004 * sin(2 * PI * 5.5 * t) * minOf(1.0, t / 0.15)
            val p1 = (t * hz * vib) % 1.0
            val p2 = (t * hz * 1.003) % 1.0
            val pulse1 = if (p1 < 0.25) 1.0 else -1.0
            val pulse2 = if (p2 < 0.5) 1.0 else -1.0
            (pulse1 * 0.6 + pulse2 * 0.4) * env * 0.26
        }
    }

    private fun renderPad(buf: FloatArray, t0: Double, dur: Double, midis: IntArray, section: Section) {
        val level = if (section == Section.INTRO) 0.11 else 0.075
        val attack = if (section == Section.INTRO) 0.6 else 0.08
        for (midi in midis) {
            val hz = midiHz(midi)
            mix(buf, t0, dur + 0.1) { t ->
                val env = minOf(1.0, t / attack) * (if (t > dur) exp(-(t - dur) * 25.0) else 1.0)
                // Triangle wave
                val p = (t * hz) % 1.0
                val tri = 4.0 * abs(p - 0.5) - 1.0
                tri * env * level / midis.size
            }
        }
    }

    /** Short 2 kHz click used as the hit sound. */
    fun tickSample(): ShortArray {
        val n = (0.03 * SAMPLE_RATE).toInt()
        return ShortArray(n) { i ->
            val t = i / SAMPLE_RATE.toDouble()
            val v = sin(2 * PI * 2000.0 * t) * exp(-t * 180.0) * 0.8 + sin(2 * PI * 5000.0 * t) * exp(-t * 400.0) * 0.3
            (v * 32767 * 0.8).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    @Suppress("unused")
    private fun floorInt(v: Double) = floor(v).toInt()
}
