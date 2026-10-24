package com.awpulent.flashrev.chart

/**
 * Rhythmic subdivision of a note within its beat, used only for colouring.
 * Matches the StepMania / FFR convention (4ths red, 8ths blue, ...).
 */
enum class Snap(val denominator: Int) {
    QUARTER(4), EIGHTH(8), TWELFTH(12), SIXTEENTH(16),
    TWENTY_FOURTH(24), THIRTY_SECOND(32), FORTY_EIGHTH(48), SIXTY_FOURTH(64), OTHER(192);

    companion object {
        /**
         * Classify a row by its position inside the measure, expressed in 192nds
         * (StepMania's finest resolution: 48 rows per beat, 192 per 4/4 measure).
         */
        fun fromRow192(row: Int): Snap = when {
            row % 48 == 0 -> QUARTER
            row % 24 == 0 -> EIGHTH
            row % 16 == 0 -> TWELFTH
            row % 12 == 0 -> SIXTEENTH
            row % 8 == 0 -> TWENTY_FOURTH
            row % 6 == 0 -> THIRTY_SECOND
            row % 4 == 0 -> FORTY_EIGHTH
            row % 3 == 0 -> SIXTY_FOURTH
            else -> OTHER
        }
    }
}

/** A single tappable note. Lanes are 0..3 = Left, Down, Up, Right. */
data class Note(
    /** Time in seconds from the start of the audio at which the note should be hit. */
    val time: Double,
    val lane: Int,
    /** Position in beats, for display/debugging. */
    val beat: Double,
    val snap: Snap,
) : Comparable<Note> {
    override fun compareTo(other: Note): Int {
        val c = time.compareTo(other.time)
        return if (c != 0) c else lane.compareTo(other.lane)
    }
}

enum class Difficulty(val displayName: String, val order: Int) {
    BEGINNER("Beginner", 0),
    EASY("Easy", 1),
    MEDIUM("Medium", 2),
    HARD("Hard", 3),
    CHALLENGE("Challenge", 4),
    EDIT("Edit", 5);

    companion object {
        fun parse(raw: String): Difficulty {
            val s = raw.trim().lowercase()
            return when {
                s.startsWith("beginner") || s == "novice" -> BEGINNER
                s.startsWith("easy") || s == "basic" || s == "light" -> EASY
                s.startsWith("medium") || s == "another" || s == "trick" || s == "standard" || s == "difficult" -> MEDIUM
                s.startsWith("hard") || s == "ssr" || s == "maniac" || s == "heavy" -> HARD
                s.startsWith("challenge") || s == "smaniac" || s == "expert" || s == "oni" -> CHALLENGE
                else -> EDIT
            }
        }
    }
}

/** One playable step chart for a song. */
data class Chart(
    val difficulty: Difficulty,
    val meter: Int,
    val description: String,
    /** Notes sorted by time, then lane. */
    val notes: List<Note>,
) {
    val noteCount: Int get() = notes.size
    val durationSeconds: Double get() = notes.lastOrNull()?.time ?: 0.0
}

/** A song folder: metadata, audio file and the charts parsed from its step file. */
data class Song(
    /** Stable identifier (folder name), used for score keys. */
    val id: String,
    val title: String,
    val subtitle: String,
    val artist: String,
    /** Absolute path to the audio file, or null if none was found. */
    val audioPath: String?,
    val charts: List<Chart>,
    val displayBpm: String,
    /** Seconds into the song where a preview would start (SAMPLESTART), if any. */
    val sampleStart: Double,
) {
    val fullTitle: String get() = if (subtitle.isBlank()) title else "$title $subtitle"
    fun chartKey(chart: Chart): String = "$id|${chart.difficulty.name}|${chart.description}"
}
