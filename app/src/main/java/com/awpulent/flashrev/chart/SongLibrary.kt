package com.awpulent.flashrev.chart

import java.io.File

/**
 * Scans the songs directory (`filesDir/songs/<song>/`) for StepMania step files and
 * builds [Song] objects. Pure JVM: only needs the directory.
 */
class SongLibrary(private val songsDir: File) {

    @Volatile private var cached: List<Song>? = null

    val songs: List<Song> get() = cached ?: load()

    fun invalidate() { cached = null }

    fun load(): List<Song> {
        val dirs = songsDir.listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()
        val list = dirs.mapNotNull { dir -> runCatching { loadSong(dir) }.getOrNull() }
            .sortedWith(compareBy({ !it.id.startsWith("_") }, { it.title.lowercase() }))
        cached = list
        return list
    }

    fun find(id: String): Song? = songs.firstOrNull { it.id == id }

    fun delete(song: Song) {
        File(songsDir, song.id).deleteRecursively()
        invalidate()
    }

    companion object {
        val STEP_EXTENSIONS = setOf("sm", "ssc")
        val AUDIO_EXTENSIONS = listOf("ogg", "mp3", "wav", "m4a", "flac", "aac", "mp4")

        fun findStepFile(dir: File): File? {
            val files = dir.listFiles() ?: return null
            // Prefer .sm (simpler, most common), fall back to .ssc.
            return files.firstOrNull { it.extension.equals("sm", true) }
                ?: files.firstOrNull { it.extension.equals("ssc", true) }
        }

        fun findAudioFile(dir: File, musicTag: String): File? {
            val files = dir.listFiles() ?: return null
            if (musicTag.isNotBlank()) {
                val wanted = File(musicTag).name
                files.firstOrNull { it.name.equals(wanted, ignoreCase = true) }?.let { return it }
            }
            for (ext in AUDIO_EXTENSIONS) {
                files.firstOrNull { it.extension.equals(ext, true) }?.let { return it }
            }
            return null
        }

        fun loadSong(dir: File): Song? {
            val stepFile = findStepFile(dir) ?: return null
            val parsed = SmParser.parse(stepFile.readText())
            if (parsed.charts.isEmpty()) return null
            val audio = findAudioFile(dir, parsed.music)
            return Song(
                id = dir.name,
                title = parsed.title.ifBlank { dir.name },
                subtitle = parsed.subtitle,
                artist = parsed.artist,
                audioPath = audio?.absolutePath,
                charts = parsed.charts,
                displayBpm = parsed.displayBpm,
                sampleStart = parsed.sampleStart,
            )
        }
    }
}
