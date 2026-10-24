package com.awpulent.flashrev.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.SystemClock

/**
 * Plays the song and exposes a smooth, monotonic song clock.
 *
 * [MediaPlayer.getCurrentPosition] is coarse and jittery, so the clock is driven by
 * [SystemClock.uptimeMillis] from an anchor that is re-synchronised gently whenever the
 * reported position drifts. Input events carry uptime timestamps, so they can be
 * converted to song time with [songTimeAt] for accurate judging.
 */
class SongPlayer(path: String) {

    private val player = MediaPlayer().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        setDataSource(path)
        prepare()
    }

    @Volatile var completed: Boolean = false; private set
    val durationSeconds: Double get() = player.duration / 1000.0

    private var playing = false
    private var anchorUptimeMs = 0L
    private var anchorPositionMs = 0.0
    private var pausedPositionMs = 0.0
    private var lastSyncUptimeMs = 0L

    init {
        player.setOnCompletionListener { completed = true }
    }

    val isPlaying: Boolean get() = playing

    fun start() {
        if (playing) return
        player.start()
        anchorUptimeMs = SystemClock.uptimeMillis()
        anchorPositionMs = player.currentPosition.toDouble()
        lastSyncUptimeMs = anchorUptimeMs
        playing = true
    }

    fun pause() {
        if (!playing) return
        player.pause()
        pausedPositionMs = player.currentPosition.toDouble()
        playing = false
    }

    fun seekTo(seconds: Double) {
        val ms = (seconds * 1000).toInt().coerceIn(0, player.duration)
        player.seekTo(ms)
        pausedPositionMs = ms.toDouble()
        anchorUptimeMs = SystemClock.uptimeMillis()
        anchorPositionMs = ms.toDouble()
        completed = false
    }

    /** Song time in seconds at the given uptime (ms, [SystemClock.uptimeMillis] base). */
    fun songTimeAt(uptimeMs: Long): Double {
        if (!playing) return pausedPositionMs / 1000.0
        return (anchorPositionMs + (uptimeMs - anchorUptimeMs)) / 1000.0
    }

    fun songTimeNow(): Double = songTimeAt(SystemClock.uptimeMillis())

    /**
     * Call once per frame from the game loop. Periodically compares the estimated
     * position with the player's reported position and corrects slowly.
     */
    fun sync() {
        if (!playing) return
        val now = SystemClock.uptimeMillis()
        if (now - lastSyncUptimeMs < SYNC_INTERVAL_MS) return
        lastSyncUptimeMs = now
        val reported = player.currentPosition.toDouble()
        val estimated = anchorPositionMs + (now - anchorUptimeMs)
        val diff = reported - estimated
        when {
            kotlin.math.abs(diff) > HARD_RESYNC_MS -> { anchorUptimeMs = now; anchorPositionMs = reported }
            else -> anchorPositionMs += diff * SOFT_CORRECTION
        }
    }

    fun release() {
        playing = false
        runCatching { player.stop() }
        player.release()
    }

    private companion object {
        const val SYNC_INTERVAL_MS = 250L
        const val HARD_RESYNC_MS = 120.0
        const val SOFT_CORRECTION = 0.15
    }
}
