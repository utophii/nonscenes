package com.nonxedy.model.recording

import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle

data class RecordingSettings(
    val progressBarEnabled: Boolean = true,
    val barStyle: BarStyle = BarStyle.SOLID,
    val barColor: BarColor = BarColor.BLUE,
    val captureIntervalTicks: Int = 1
) {
    companion object {
        const val MAX_DURATION_SECONDS: Int = 300

        fun isValidDuration(seconds: Int?): Boolean = seconds != null && seconds in 1..MAX_DURATION_SECONDS
    }
}
