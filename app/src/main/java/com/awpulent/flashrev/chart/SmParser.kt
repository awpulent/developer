package com.awpulent.flashrev.chart

/**
 * Parser for StepMania `.sm` and `.ssc` step files, restricted to the 4-panel
 * `dance-single` style that FFR uses.
 *
 * FFR has no hold notes, so hold and roll heads are converted into plain taps,
 * and mines, lifts and fakes are dropped.
 */
object SmParser {

    class ParsedFile(
        val title: String,
        val subtitle: String,
        val artist: String,
        val music: String,
        val displayBpm: String,
        val sampleStart: Double,
        val charts: List<Chart>,
    )

    private class RawChart {
        var stepsType = ""
        var difficulty = ""
        var meter = ""
        var description = ""
        var noteData = ""
        var bpms: String? = null
        var stops: String? = null
        var offset: String? = null
    }

    fun parse(text: String): ParsedFile {
        val header = HashMap<String, String>()
        val rawCharts = ArrayList<RawChart>()
        var current: RawChart? = null // non-null while inside an .ssc #NOTEDATA block

        for ((key, value) in tags(text)) {
            val k = key.uppercase()
            when {
                k == "NOTEDATA" -> { // .ssc chart block begins
                    current = RawChart().also { rawCharts.add(it) }
                }
                k == "NOTES" && current == null -> { // .sm style: everything in one tag
                    val parts = value.split(':')
                    if (parts.size >= 6) {
                        rawCharts.add(RawChart().apply {
                            stepsType = parts[0].trim()
                            description = parts[1].trim()
                            difficulty = parts[2].trim()
                            meter = parts[3].trim()
                            noteData = parts.drop(5).joinToString(":")
                        })
                    }
                }
                current != null -> when (k) {
                    "STEPSTYPE" -> current.stepsType = value.trim()
                    "DIFFICULTY" -> current.difficulty = value.trim()
                    "METER" -> current.meter = value.trim()
                    "DESCRIPTION", "CHARTNAME" -> if (current.description.isBlank()) current.description = value.trim()
                    "BPMS" -> current.bpms = value
                    "STOPS" -> current.stops = value
                    "OFFSET" -> current.offset = value
                    "NOTES", "NOTES2" -> {
                        current.noteData = value
                        current = null // chart block ends with its notes
                    }
                }
                else -> header.putIfAbsent(k, value)
            }
        }

        val songOffset = header["OFFSET"]?.trim()?.toDoubleOrNull() ?: 0.0
        val songBpms = TimingData.parsePairs(header["BPMS"] ?: "")
        val songStops = TimingData.parsePairs(header["STOPS"] ?: "")

        val charts = rawCharts
            .filter { it.stepsType.equals("dance-single", ignoreCase = true) }
            .mapNotNull { raw ->
                val timing = TimingData(
                    offset = raw.offset?.trim()?.toDoubleOrNull() ?: songOffset,
                    bpms = raw.bpms?.let { TimingData.parsePairs(it) }?.ifEmpty { songBpms } ?: songBpms,
                    stops = raw.stops?.let { TimingData.parsePairs(it) } ?: songStops,
                )
                val notes = parseNoteData(raw.noteData, timing)
                if (notes.isEmpty()) return@mapNotNull null
                Chart(
                    difficulty = Difficulty.parse(raw.difficulty),
                    meter = raw.meter.trim().toIntOrNull() ?: 0,
                    description = raw.description,
                    notes = notes,
                )
            }
            .sortedWith(compareBy({ it.difficulty.order }, { it.meter }))

        return ParsedFile(
            title = header["TITLE"]?.trim().orEmpty(),
            subtitle = header["SUBTITLE"]?.trim().orEmpty(),
            artist = header["ARTIST"]?.trim().orEmpty(),
            music = header["MUSIC"]?.trim().orEmpty(),
            displayBpm = displayBpm(header["DISPLAYBPM"], TimingData(songOffset, songBpms, songStops)),
            sampleStart = header["SAMPLESTART"]?.trim()?.toDoubleOrNull() ?: 0.0,
            charts = charts,
        )
    }

    /**
     * Splits the file into `#KEY:VALUE;` tags, ignoring `//` comments.
     * Values may contain ':' (e.g. .sm #NOTES), so only the first ':' separates key from value.
     */
    internal fun tags(text: String): List<Pair<String, String>> {
        val stripped = StringBuilder(text.length)
        for (line in text.lineSequence()) {
            val idx = line.indexOf("//")
            stripped.append(if (idx >= 0) line.substring(0, idx) else line).append('\n')
        }
        val result = ArrayList<Pair<String, String>>()
        var i = 0
        val s = stripped
        while (i < s.length) {
            val hash = s.indexOf('#', i)
            if (hash < 0) break
            val end = s.indexOf(';', hash)
            if (end < 0) break
            val body = s.substring(hash + 1, end)
            val colon = body.indexOf(':')
            if (colon > 0) {
                result.add(body.substring(0, colon).trim() to body.substring(colon + 1))
            }
            i = end + 1
        }
        return result
    }

    internal fun parseNoteData(noteData: String, timing: TimingData): List<Note> {
        val notes = ArrayList<Note>()
        val measures = noteData.split(',')
        for ((measureIndex, measure) in measures.withIndex()) {
            val rows = measure.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
            if (rows.isEmpty()) continue
            val rowCount = rows.size
            for ((rowIndex, row) in rows.withIndex()) {
                val beat = measureIndex * 4.0 + 4.0 * rowIndex / rowCount
                val row192 = if (192 % rowCount == 0) rowIndex * (192 / rowCount) else -1
                val snap = if (row192 >= 0) Snap.fromRow192(row192) else Snap.OTHER
                val time = timing.beatToTime(beat)
                var lane = 0
                var c = 0
                while (c < row.length && lane < 4) {
                    val ch = row[c]
                    // Keysound / attack annotations like "1{...}" or "1[...]" follow the note char.
                    if (ch == '{') { c = row.indexOf('}', c).let { if (it < 0) row.length else it + 1 }; continue }
                    if (ch == '[') { c = row.indexOf(']', c).let { if (it < 0) row.length else it + 1 }; continue }
                    when (ch) {
                        '1', '2', '4' -> notes.add(Note(time, lane, beat, snap)) // tap, hold head, roll head
                        // '0' empty, '3' hold tail, 'M' mine, 'L' lift, 'F' fake, 'K' keysound: not playable in FFR
                    }
                    lane++
                    c++
                }
            }
        }
        notes.sort()
        return notes
    }

    private fun displayBpm(raw: String?, timing: TimingData): String {
        val fmt = { v: Double -> if (v == Math.rint(v)) v.toInt().toString() else String.format("%.1f", v) }
        val trimmed = raw?.trim()
        if (!trimmed.isNullOrEmpty() && trimmed != "*") {
            val parts = trimmed.split(':').mapNotNull { it.trim().toDoubleOrNull() }
            if (parts.size == 1) return fmt(parts[0])
            if (parts.size >= 2) return "${fmt(parts[0])}–${fmt(parts[1])}"
        }
        val lo = timing.minBpm
        val hi = timing.maxBpm
        return if (lo == hi) fmt(lo) else "${fmt(lo)}–${fmt(hi)}"
    }
}
