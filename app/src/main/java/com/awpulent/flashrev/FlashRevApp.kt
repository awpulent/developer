package com.awpulent.flashrev

import android.app.Application
import com.awpulent.flashrev.chart.SongLibrary
import com.awpulent.flashrev.data.ScoreStore
import com.awpulent.flashrev.data.Settings
import java.io.File

class FlashRevApp : Application() {
    val settings: Settings by lazy { Settings(this) }
    val scores: ScoreStore by lazy { ScoreStore(this) }
    val songsDir: File by lazy { File(filesDir, "songs").also { it.mkdirs() } }
    val library: SongLibrary by lazy { SongLibrary(songsDir) }

    companion object {
        fun from(context: android.content.Context): FlashRevApp = context.applicationContext as FlashRevApp
    }
}
