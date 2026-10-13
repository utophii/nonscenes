package com.nonxedy.model.playback

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FadeSettingsTest {

    @Test
    fun `default fade uses blindness`() {
        val fade = FadeSettings()
        assertEquals(FadeMode.BLINDNESS, fade.mode)
        assertTrue(fade.enabled)
        assertEquals(500L, fade.outMs)
        assertEquals(40, fade.totalTicks)
    }

    @Test
    fun `title mode without a glyph falls back to blindness`() {
        assertEquals(FadeMode.BLINDNESS, FadeSettings(mode = FadeMode.TITLE).mode)
        assertEquals(FadeMode.TITLE, FadeSettings(mode = FadeMode.TITLE, titleGlyph = "").mode)
    }

    @Test
    fun `disabled fade does not delay the camera`() {
        val fade = FadeSettings(mode = FadeMode.NONE, outTicks = 40)
        assertFalse(fade.enabled)
        assertEquals(0L, fade.outMs)
    }

    @Test
    fun `negative ticks are clamped and hold is at least one tick`() {
        val fade = FadeSettings(outTicks = -5, holdTicks = 0, inTicks = -1)
        assertEquals(0, fade.outTicks)
        assertEquals(1, fade.holdTicks)
        assertEquals(0, fade.inTicks)
    }
}
