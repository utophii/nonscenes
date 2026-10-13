package com.nonxedy.model.playback

// fade to black around the camera cut at the start and end of a cutscene.
// outTicks darkens the screen, holdTicks stays black while the camera
// switches, inTicks reveals the new view
data class FadeSettings private constructor(
    val mode: FadeMode,
    val outTicks: Int,
    val holdTicks: Int,
    val inTicks: Int,
    val titleGlyph: String
) {
    val enabled: Boolean
        get() = mode != FadeMode.NONE

    // time from starting a fade until the screen is fully black
    val outMs: Long
        get() = if (enabled) outTicks * 50L else 0L

    val totalTicks: Int
        get() = outTicks + holdTicks + inTicks

    companion object {
        operator fun invoke(
            mode: FadeMode = FadeMode.BLINDNESS,
            outTicks: Int = 10,
            holdTicks: Int = 10,
            inTicks: Int = 20,
            titleGlyph: String = ""
        ): FadeSettings {
            val resolvedMode = if (mode == FadeMode.TITLE && titleGlyph.isBlank()) FadeMode.BLINDNESS else mode
            return FadeSettings(
                mode = resolvedMode,
                outTicks = outTicks.coerceAtLeast(0),
                holdTicks = holdTicks.coerceAtLeast(1),
                inTicks = inTicks.coerceAtLeast(0),
                titleGlyph = titleGlyph
            )
        }
    }
}

enum class FadeMode {
    NONE,
    // works without a resource pack. The client eases out of blindness on its own
    BLINDNESS,
    // a full-screen glyph from a resource pack shown as a title
    TITLE
}
