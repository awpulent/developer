package com.awpulent.flashrev.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.awpulent.flashrev.FlashRevApp
import com.awpulent.flashrev.R
import com.awpulent.flashrev.data.Settings
import kotlin.math.roundToInt

class SettingsActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var speedBar: SeekBar
    private lateinit var speedValue: TextView
    private lateinit var offsetBar: SeekBar
    private lateinit var offsetValue: TextView
    private lateinit var laneBar: SeekBar
    private lateinit var laneValue: TextView
    private lateinit var dirUp: RadioButton
    private lateinit var dirDown: RadioButton
    private lateinit var hitSounds: Switch
    private lateinit var earlyLate: Switch
    private lateinit var snapColors: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<View>(R.id.root).padForSystemBars()
        settings = FlashRevApp.from(this).settings

        speedBar = findViewById(R.id.speed_bar)
        speedValue = findViewById(R.id.speed_value)
        offsetBar = findViewById(R.id.offset_bar)
        offsetValue = findViewById(R.id.offset_value)
        laneBar = findViewById(R.id.lane_bar)
        laneValue = findViewById(R.id.lane_value)
        dirUp = findViewById(R.id.dir_up)
        dirDown = findViewById(R.id.dir_down)
        hitSounds = findViewById(R.id.hit_sounds)
        earlyLate = findViewById(R.id.early_late)
        snapColors = findViewById(R.id.snap_colors)

        speedBar.max = ((Settings.MAX_SPEED - Settings.MIN_SPEED) * 10).roundToInt()
        offsetBar.max = Settings.MAX_OFFSET - Settings.MIN_OFFSET
        laneBar.max = Settings.MAX_LANE_WIDTH - Settings.MIN_LANE_WIDTH

        speedBar.setOnSeekBarChangeListener(onChange { p -> settings.scrollSpeed = Settings.MIN_SPEED + p / 10f; refreshValues() })
        offsetBar.setOnSeekBarChangeListener(onChange { p -> settings.globalOffsetMs = Settings.MIN_OFFSET + p; refreshValues() })
        laneBar.setOnSeekBarChangeListener(onChange { p -> settings.laneWidthPercent = Settings.MIN_LANE_WIDTH + p; refreshValues() })
        findViewById<View>(R.id.offset_minus).setOnClickListener { settings.globalOffsetMs -= 5; loadValues() }
        findViewById<View>(R.id.offset_plus).setOnClickListener { settings.globalOffsetMs += 5; loadValues() }
        findViewById<View>(R.id.btn_calibrate).setOnClickListener { startActivity(Intent(this, CalibrationActivity::class.java)) }

        dirUp.setOnCheckedChangeListener { _, checked -> if (checked) settings.scrollDirection = Settings.ScrollDirection.UP }
        dirDown.setOnCheckedChangeListener { _, checked -> if (checked) settings.scrollDirection = Settings.ScrollDirection.DOWN }
        hitSounds.setOnCheckedChangeListener { _, checked -> settings.hitSounds = checked }
        earlyLate.setOnCheckedChangeListener { _, checked -> settings.showEarlyLate = checked }
        snapColors.setOnCheckedChangeListener { _, checked -> settings.snapColors = checked }

        findViewById<View>(R.id.btn_reset).setOnClickListener { settings.resetGameplay(); loadValues() }
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        loadValues()
    }

    private fun loadValues() {
        speedBar.progress = ((settings.scrollSpeed - Settings.MIN_SPEED) * 10).roundToInt()
        offsetBar.progress = settings.globalOffsetMs - Settings.MIN_OFFSET
        laneBar.progress = settings.laneWidthPercent - Settings.MIN_LANE_WIDTH
        dirUp.isChecked = settings.scrollDirection == Settings.ScrollDirection.UP
        dirDown.isChecked = settings.scrollDirection == Settings.ScrollDirection.DOWN
        hitSounds.isChecked = settings.hitSounds
        earlyLate.isChecked = settings.showEarlyLate
        snapColors.isChecked = settings.snapColors
        refreshValues()
    }

    private fun refreshValues() {
        speedValue.text = getString(R.string.speed_value, settings.scrollSpeed)
        offsetValue.text = getString(R.string.setting_offset_value, settings.globalOffsetMs)
        laneValue.text = getString(R.string.percent_value, settings.laneWidthPercent)
    }

    private fun onChange(block: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) { if (fromUser) block(progress) }
        override fun onStartTrackingTouch(seekBar: SeekBar) {}
        override fun onStopTrackingTouch(seekBar: SeekBar) {}
    }
}
