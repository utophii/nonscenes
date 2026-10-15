package com.nonxedy.model.playback

data class PlaybackSettings private constructor(
    val updateRate: Int,
    val interpolation: InterpolationType = InterpolationType.CATMULL_ROM,
    val smoothRotation: Boolean = true,
    val eyeHeight: Double = 1.62,
    val fade: FadeSettings = FadeSettings()
) {
    val updateIntervalMs: Long
        get() = 1000L / updateRate

    companion object {
        const val MIN_UPDATE_RATE: Int = 20
        const val MAX_UPDATE_RATE: Int = 240

        operator fun invoke(
            updateRate: Int = 60,
            interpolation: InterpolationType = InterpolationType.CATMULL_ROM,
            smoothRotation: Boolean = true,
            eyeHeight: Double = 1.62,
            fade: FadeSettings = FadeSettings()
        ): PlaybackSettings {
            return PlaybackSettings(
                updateRate = updateRate.coerceIn(MIN_UPDATE_RATE, MAX_UPDATE_RATE),
                interpolation = interpolation,
                smoothRotation = smoothRotation,
                eyeHeight = eyeHeight,
                fade = fade
            )
        }
    }
}

enum class InterpolationType {
    CATMULL_ROM,
    BEZIER
}
