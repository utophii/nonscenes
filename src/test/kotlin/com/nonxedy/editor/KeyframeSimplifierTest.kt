package com.nonxedy.editor

import com.nonxedy.model.Cutscene
import com.nonxedy.model.CutsceneFrame
import org.bukkit.Location
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class KeyframeSimplifierTest {

    private fun raw(x: Double, z: Double = 0.0, world: String = "world", yaw: Float = 0f) =
        CutsceneFrame(Location(null, x, 64.0, z, yaw, 0f), world)

    @Test
    fun `constant-speed straight line collapses to two keyframes`() {
        val frames = (0..100).map { raw(it * 0.2) }
        val cutscene = Cutscene("line", frames, 50L)
        val keys = KeyframeSimplifier.simplify(cutscene, 0.25, 3.0)
        assertEquals(2, keys.size)
        assertEquals(5000L, keys[0].segmentMs)
        assertNull(keys[1].segmentMs)
    }

    @Test
    fun `total duration is preserved`() {
        val frames = (0..200).map { i -> raw(i * 0.1, z = if (i < 100) 0.0 else (i - 100) * 0.1) }
        val cutscene = Cutscene("corner", frames, 50L)
        val keys = KeyframeSimplifier.simplify(cutscene, 0.25, 3.0)
        val simplified = cutscene.copy(frames = keys)
        assertEquals(cutscene.totalDurationMs, simplified.totalDurationMs)
        assertFalse(simplified.isRawRecording)
        // the corner survives
        assertTrue(keys.any { it.location.x == 10.0 && it.location.z == 0.0 }, "corner keyframe was dropped")
    }

    @Test
    fun `a stop in the middle of a straight line is kept`() {
        // moves, waits 2 seconds, moves again
        val frames = (0..40).map { raw(it * 0.25) } +
            List(40) { raw(10.0) } +
            (1..40).map { raw(10.0 + it * 0.25) }
        val keys = KeyframeSimplifier.simplify(Cutscene("pause", frames, 50L), 0.25, 3.0)
        assertTrue(keys.size >= 4, "pause was smoothed away: ${keys.size} keys")
    }

    @Test
    fun `a turn of more than half a circle is kept`() {
        // two keyframes alone would turn the short way round
        val frames = (0..60).map { raw(0.0, yaw = it * 4.5f) }
        val keys = KeyframeSimplifier.simplify(Cutscene("look", frames, 50L), 0.25, 3.0)
        assertTrue(keys.size > 2)
    }

    @Test
    fun `world changes keep both sides`() {
        val frames = (0..20).map { raw(it.toDouble()) } + (0..20).map { raw(it.toDouble(), world = "nether") }
        val keys = KeyframeSimplifier.simplify(Cutscene("worlds", frames, 50L), 0.25, 3.0)
        assertEquals(listOf("world", "world", "nether", "nether"), keys.map { it.worldName })
    }
}
