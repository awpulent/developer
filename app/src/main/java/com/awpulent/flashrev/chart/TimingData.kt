package com.awpulent.flashrev.chart

/**
 * Converts beats to seconds using StepMania-style timing data:
 * an offset, a list of BPM changes and a list of stops.
 *
 * `offset` follows the .sm convention: beat 0 occurs at `-offset` seconds into the audio.
 */
class TimingData(
    val offset: Double,
    bpms: List<Pair<Double, Double>>,
    stops: List<Pair<Double, Double>>,
) {
    private val bpmChanges: List<Pair<Double, Double>> =
        bpms.filter { it.second > 0 }.sortedBy { it.first }.ifEmpty { listOf(0.0 to 120.0) }
    private val stopList: List<Pair<Double, Double>> = stops.sortedBy { it.first }

    /** Seconds elapsed from beat 0 up to the start of each BPM segment. */
    private val segmentStartTimes: DoubleArray = DoubleArray(bpmChanges.size).also { arr ->
        var t = 0.0
        for (i in bpmChanges.indices) {
            arr[i] = t
            if (i + 1 < bpmChanges.size) {
                val (beat, bpm) = bpmChanges[i]
                val nextBeat = bpmChanges[i + 1].first
                t += (nextBeat - beat) * 60.0 / bpm
            }
        }
    }

    val initialBpm: Double get() = bpmChanges.first().second

    val minBpm: Double get() = bpmChanges.minOf { it.second }
    val maxBpm: Double get() = bpmChanges.maxOf { it.second }

    /** Time in seconds (relative to the audio start) at which [beat] occurs. */
    fun beatToTime(beat: Double): Double {
        // Find the BPM segment containing this beat.
        var idx = 0
        while (idx + 1 < bpmChanges.size && bpmChanges[idx + 1].first <= beat) idx++
        val (segBeat, bpm) = bpmChanges[idx]
        var t = segmentStartTimes[idx] + (beat - segBeat) * 60.0 / bpm
        // A stop at beat b delays everything strictly after b.
        for ((stopBeat, seconds) in stopList) {
            if (stopBeat < beat) t += seconds else break
        }
        return t - offset
    }

    companion object {
        /** Parses "beat=value,beat=value" lists as used by #BPMS and #STOPS. */
        fun parsePairs(raw: String): List<Pair<Double, Double>> =
            raw.split(',').mapNotNull { entry ->
                val parts = entry.trim().split('=')
                if (parts.size != 2) return@mapNotNull null
                val a = parts[0].trim().toDoubleOrNull() ?: return@mapNotNull null
                val b = parts[1].trim().toDoubleOrNull() ?: return@mapNotNull null
                a to b
            }
    }
}
