package com.nonxedy.model.playback

data class PlaybackSettings private constructor(
    val updateRate: Int,
    val interpolation: InterpolationType = InterpolationType.CATMULL_ROM,
    val smoothRotation: Boolean = true,
    val bakePath: Boolean = true,
    val rideHeightOffset: Double = 0.75
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
            bakePath: Boolean = true,
            rideHeightOffset: Double = 0.75
        ): PlaybackSettings {
            return PlaybackSettings(
                updateRate = updateRate.coerceIn(MIN_UPDATE_RATE, MAX_UPDATE_RATE),
                interpolation = interpolation,
                smoothRotation = smoothRotation,
                bakePath = bakePath,
                rideHeightOffset = rideHeightOffset
            )
        }
    }
}

enum class InterpolationType {
    CATMULL_ROM,
    BEZIER
}
