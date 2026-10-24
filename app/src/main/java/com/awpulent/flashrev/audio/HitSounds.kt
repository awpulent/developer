package com.awpulent.flashrev.audio

import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File

/** Low-latency click played on note hits, using a synthesised sample in the app's files dir. */
class HitSounds(tickFile: File) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    @Volatile private var loaded = false
    private val tickId: Int

    init {
        pool.setOnLoadCompleteListener { _, _, status -> loaded = status == 0 }
        tickId = pool.load(tickFile.absolutePath, 1)
    }

    fun play() {
        if (loaded) pool.play(tickId, 0.6f, 0.6f, 1, 0, 1f)
    }

    fun release() = pool.release()
}
