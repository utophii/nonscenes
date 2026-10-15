package com.nonxedy.editor

import com.nonxedy.model.Cutscene
import com.nonxedy.model.CutsceneFrame
import org.bukkit.Location
import org.bukkit.World
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class KeyframeTrackTest {

    private val world: World = Mockito.mock(World::class.java).also {
        Mockito.`when`(it.name).thenReturn("world")
    }

    private fun at(x: Double) = Location(world, x, 64.0, 0.0)
    private fun key(x: Double, segmentMs: Long? = null) = CutsceneFrame(at(x), "world", segmentMs)

    private fun track() = KeyframeTrack(listOf(key(0.0, 2000), key(10.0, 1000), key(20.0)), 3000)

    @Test
    fun `inserting splits the segment by distance and keeps total time`() {
        val t = track()
        t.insert(1, at(2.5))
        assertEquals(4, t.size)
        assertEquals(500L, t.segmentMs(0))
        assertEquals(1500L, t.segmentMs(1))
        assertEquals(3000L, t.totalMs)
    }

    @Test
    fun `appending keeps the speed of the last segment`() {
        val t = track()
        t.insert(3, at(40.0))
        assertEquals(2000L, t.segmentMs(2)) // 20 blocks at 10 blocks per second
        assertNull(t[3].segmentMs)
    }

    @Test
    fun `prepending keeps the speed of the first segment`() {
        val t = track()
        t.insert(0, at(-5.0))
        assertEquals(1000L, t.segmentMs(0)) // 5 blocks at 5 blocks per second
        assertEquals(2000L, t.segmentMs(1))
    }

    @Test
    fun `first keyframes use the default duration`() {
        val t = KeyframeTrack(emptyList(), 3000)
        t.insert(0, at(0.0))
        t.insert(1, at(10.0))
        assertEquals(3000L, t.segmentMs(0))
    }

    @Test
    fun `removing a middle keyframe merges its segments`() {
        val t = track()
        t.remove(1)
        assertEquals(2, t.size)
        assertEquals(3000L, t.segmentMs(0))
        assertNull(t[1].segmentMs)
    }

    @Test
    fun `removing the last keyframe drops its incoming segment`() {
        val t = track()
        t.remove(2)
        assertEquals(2000L, t.totalMs)
        assertNull(t[1].segmentMs)
    }

    @Test
    fun `moving keeps timing`() {
        val t = track()
        t.move(1, at(15.0))
        assertEquals(15.0, t[1].location.x)
        assertEquals(1000L, t.segmentMs(1))
    }

    @Test
    fun `segment durations are clamped`() {
        val t = track()
        t.setSegment(0, 1)
        assertEquals(Cutscene.MIN_SEGMENT_MS, t.segmentMs(0))
        assertThrows(IllegalArgumentException::class.java) { t.setSegment(2, 1000) }
    }

    @Test
    fun `saved cutscene is no longer a raw recording`() {
        val raw = Cutscene("r", (0..10).map { key(it.toDouble()) }, 50L)
        assertTrue(raw.isRawRecording)
        val edited = KeyframeTrack.from(raw, 2000, 0.25, 3.0).toCutscene("r", raw)
        assertFalse(edited.isRawRecording)
        assertEquals(raw.totalDurationMs, edited.totalDurationMs)
    }
}
