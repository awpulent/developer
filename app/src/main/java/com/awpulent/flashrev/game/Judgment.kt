package com.awpulent.flashrev.game

/**
 * FFR judgments. Windows are the classic FFR half-frame steps at 30 fps:
 * ±17 ms Amazing, ±50 ms Perfect, ±83 ms Good, ±117 ms Average.
 * Anything hit outside ±117 ms is impossible: a note that scrolls past unhit is a
 * Miss, and a tap with no note in range is a Boo.
 */
enum class Judgment(
    val label: String,
    /** Points added to the raw score. */
    val rawPoints: Int,
    /** Maximum absolute timing error (ms) that still earns this judgment; 0 for Miss/Boo. */
    val windowMs: Int,
    /** Whether this judgment extends the combo. */
    val extendsCombo: Boolean,
    /** Whether this judgment resets the combo. Boos cost points but keep the combo (FFR "blackflag"). */
    val breaksCombo: Boolean,
) {
    AMAZING("Amazing", 50, 17, extendsCombo = true, breaksCombo = false),
    PERFECT("Perfect", 50, 50, extendsCombo = true, breaksCombo = false),
    GOOD("Good", 25, 83, extendsCombo = true, breaksCombo = false),
    AVERAGE("Average", 5, 117, extendsCombo = true, breaksCombo = false),
    MISS("Miss", -10, 0, extendsCombo = false, breaksCombo = true),
    BOO("Boo", -5, 0, extendsCombo = false, breaksCombo = false);

    companion object {
        /** The widest hit window; notes further away than this are not hittable. */
        const val MAX_WINDOW_MS: Int = 117

        /** Judgment for a timing error of [errorMs] (absolute value is used). */
        fun forError(errorMs: Double): Judgment {
            val e = kotlin.math.abs(errorMs)
            return when {
                e <= AMAZING.windowMs -> AMAZING
                e <= PERFECT.windowMs -> PERFECT
                e <= GOOD.windowMs -> GOOD
                e <= AVERAGE.windowMs -> AVERAGE
                else -> MISS
            }
        }
    }
}
