package com.awpulent.flashrev.demo

import android.content.Context
import com.awpulent.flashrev.audio.WavWriter
import com.awpulent.flashrev.data.Settings
import java.io.File

/** Writes the generated demo song and hit-sound sample into the app's files directory. */
object DemoInstaller {
    const val DEMO_DIR_NAME = "_demo"
    private const val TICK_FILE = "tick.wav"

    fun demoDir(context: Context): File = File(File(context.filesDir, "songs"), DEMO_DIR_NAME)
    fun tickFile(context: Context): File = File(context.filesDir, TICK_FILE)

    fun isInstalled(context: Context, settings: Settings): Boolean {
        val dir = demoDir(context)
        return settings.demoVersion == DemoSongGenerator.VERSION &&
            File(dir, DemoSongGenerator.AUDIO_FILE).length() > 1000 &&
            File(dir, DemoSongGenerator.STEP_FILE).exists() &&
            tickFile(context).exists()
    }

    /** Generates and writes all demo assets. Slow (about a second); call off the main thread. */
    fun install(context: Context, settings: Settings) {
        val dir = demoDir(context)
        dir.mkdirs()
        val generated = DemoSongGenerator.generate()
        WavWriter.write(File(dir, DemoSongGenerator.AUDIO_FILE), generated.pcm, DemoSongGenerator.SAMPLE_RATE)
        File(dir, DemoSongGenerator.STEP_FILE).writeText(generated.smText)
        WavWriter.write(tickFile(context), DemoSongGenerator.tickSample(), DemoSongGenerator.SAMPLE_RATE)
        settings.demoVersion = DemoSongGenerator.VERSION
    }
}
