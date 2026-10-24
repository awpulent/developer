package com.awpulent.flashrev.data

import android.content.Context
import android.content.SharedPreferences

/** User preferences backed by SharedPreferences. */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    enum class ScrollDirection { UP, DOWN }

    /** Multiplier on the base scroll rate. 1.0 = an arrow crosses the play field in about a second. */
    var scrollSpeed: Float
        get() = prefs.getFloat(KEY_SPEED, DEFAULT_SPEED).coerceIn(MIN_SPEED, MAX_SPEED)
        set(value) = prefs.edit().putFloat(KEY_SPEED, value.coerceIn(MIN_SPEED, MAX_SPEED)).apply()

    var scrollDirection: ScrollDirection
        get() = runCatching { ScrollDirection.valueOf(prefs.getString(KEY_DIRECTION, null) ?: "") }
            .getOrDefault(ScrollDirection.UP)
        set(value) = prefs.edit().putString(KEY_DIRECTION, value.name).apply()

    /** Global audio/input offset in milliseconds. Positive shifts notes later. */
    var globalOffsetMs: Int
        get() = prefs.getInt(KEY_OFFSET, 0).coerceIn(MIN_OFFSET, MAX_OFFSET)
        set(value) = prefs.edit().putInt(KEY_OFFSET, value.coerceIn(MIN_OFFSET, MAX_OFFSET)).apply()

    var hitSounds: Boolean
        get() = prefs.getBoolean(KEY_HIT_SOUNDS, false)
        set(value) = prefs.edit().putBoolean(KEY_HIT_SOUNDS, value).apply()

    var showEarlyLate: Boolean
        get() = prefs.getBoolean(KEY_EARLY_LATE, true)
        set(value) = prefs.edit().putBoolean(KEY_EARLY_LATE, value).apply()

    var snapColors: Boolean
        get() = prefs.getBoolean(KEY_SNAP_COLORS, true)
        set(value) = prefs.edit().putBoolean(KEY_SNAP_COLORS, value).apply()

    /** Fraction of the screen width used by the four lanes together. */
    var laneWidthPercent: Int
        get() = prefs.getInt(KEY_LANE_WIDTH, DEFAULT_LANE_WIDTH).coerceIn(MIN_LANE_WIDTH, MAX_LANE_WIDTH)
        set(value) = prefs.edit().putInt(KEY_LANE_WIDTH, value.coerceIn(MIN_LANE_WIDTH, MAX_LANE_WIDTH)).apply()

    /** Version of the generated demo song that has been written to disk. */
    var demoVersion: Int
        get() = prefs.getInt(KEY_DEMO_VERSION, 0)
        set(value) = prefs.edit().putInt(KEY_DEMO_VERSION, value).apply()

    fun resetGameplay() {
        prefs.edit()
            .remove(KEY_SPEED).remove(KEY_DIRECTION).remove(KEY_OFFSET).remove(KEY_HIT_SOUNDS)
            .remove(KEY_EARLY_LATE).remove(KEY_SNAP_COLORS).remove(KEY_LANE_WIDTH)
            .apply()
    }

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 5.0f
        const val DEFAULT_SPEED = 2.0f
        const val MIN_OFFSET = -300
        const val MAX_OFFSET = 300
        const val MIN_LANE_WIDTH = 50
        const val MAX_LANE_WIDTH = 100
        const val DEFAULT_LANE_WIDTH = 80

        private const val KEY_SPEED = "scroll_speed"
        private const val KEY_DIRECTION = "scroll_direction"
        private const val KEY_OFFSET = "global_offset_ms"
        private const val KEY_HIT_SOUNDS = "hit_sounds"
        private const val KEY_EARLY_LATE = "show_early_late"
        private const val KEY_SNAP_COLORS = "snap_colors"
        private const val KEY_LANE_WIDTH = "lane_width_percent"
        private const val KEY_DEMO_VERSION = "demo_version"
    }
}
