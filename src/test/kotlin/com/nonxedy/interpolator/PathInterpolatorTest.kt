package com.nonxedy.interpolator

import com.nonxedy.model.CutsceneFrame
import org.bukkit.Location
import org.bukkit.World
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class PathInterpolatorTest {

    private val world: World = Mockito.mock(World::class.java)

    private fun frame(x: Double, y: Double, z: Double, yaw: Float = 0f, pitch: Float = 0f): CutsceneFrame {
        return CutsceneFrame(Location(world, x, y, z, yaw, pitch), "world")
    }

    @Test
    fun `catmull-rom interpolator returns middle at t 0_5 with 3 frames`() {
        val frames = listOf(frame(0.0, 0.0, 0.0), frame(5.0, 0.0, 0.0), frame(10.0, 0.0, 0.0))
        val interp = CatmullRomPathInterpolator()
        val point = interp.interpolate(frames, 0.5)
        assertTrue(point.x in 4.0..6.0, "Expected middle-ish x, got ${point.x}")
    }

    @Test
    fun `bezier interpolator returns start at t 0`() {
        val frames = listOf(frame(0.0, 0.0, 0.0), frame(10.0, 10.0, 10.0))
        val interp = BezierPathInterpolator()
        val point = interp.interpolate(frames, 0.0)
        assertEquals(0.0, point.x, 0.001)
    }

    @Test
    fun `bezier interpolator returns end at t 1`() {
        val frames = listOf(frame(0.0, 0.0, 0.0), frame(10.0, 10.0, 10.0))
        val interp = BezierPathInterpolator()
        val point = interp.interpolate(frames, 1.0)
        assertEquals(10.0, point.x, 0.001)
    }

    @Test
    fun `does not blend coordinates across different worlds`() {
        val overworld = Mockito.mock(World::class.java)
        val nether = Mockito.mock(World::class.java)
        Mockito.`when`(overworld.name).thenReturn("world")
        Mockito.`when`(nether.name).thenReturn("world_nether")

        val frames = listOf(
            CutsceneFrame(Location(overworld, 0.0, 64.0, 0.0), "world"),
            CutsceneFrame(Location(nether, 100.0, 32.0, 100.0), "world_nether")
        )
        listOf(CatmullRomPathInterpolator(), BezierPathInterpolator()).forEach { interp ->
            val before = interp.interpolate(frames, 0.25)
            assertEquals("world", before.worldName, "${interp::class.simpleName} mixed worlds too early")
            assertEquals(0.0, before.x, 0.001)

            val end = interp.interpolate(frames, 1.0)
            assertEquals("world_nether", end.worldName, "${interp::class.simpleName} missed destination world")
            assertEquals(100.0, end.x, 0.001)
        }
    }

    @Test
    fun `single frame always returns same point`() {
        val frames = listOf(frame(3.0, 4.0, 5.0))
        listOf(CatmullRomPathInterpolator(), BezierPathInterpolator()).forEach { interp ->
            val point = interp.interpolate(frames, 0.5)
            assertEquals(3.0, point.x, 0.001, "${interp::class.simpleName} failed on single frame")
            assertEquals(4.0, point.y, 0.001, "${interp::class.simpleName} failed on single frame")
            assertEquals(5.0, point.z, 0.001, "${interp::class.simpleName} failed on single frame")
        }
    }
}
