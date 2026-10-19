package com.nonxedy.playback

import com.nonxedy.interpolator.BezierPathInterpolator
import com.nonxedy.interpolator.CatmullRomPathInterpolator
import com.nonxedy.model.Cutscene
import com.nonxedy.model.CutsceneFrame
import com.nonxedy.model.playback.PlaybackSettings
import org.bukkit.Location
import org.bukkit.World
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito

class PathBakerTest {

    private fun mockWorld(name: String): World = Mockito.mock(World::class.java).also {
        Mockito.`when`(it.name).thenReturn(name)
    }

    private val overworld = mockWorld("world")
    private val nether = mockWorld("world_nether")
    private val worlds: (String) -> World? = mapOf("world" to overworld, "world_nether" to nether)::get

    private val interpolators = listOf(CatmullRomPathInterpolator(), BezierPathInterpolator())

    private fun key(x: Double, z: Double, segmentMs: Long? = null, world: String = "world") =
        CutsceneFrame(Location(null, x, 64.0, z, x.toFloat(), 0f), world, segmentMs)

    private val cutscene = Cutscene(
        "bake",
        listOf(key(0.0, 0.0, 1200), key(10.0, 0.0, 800), key(10.0, 10.0))
    )

    @Test
    fun `one sample per update interval plus the end`() {
        val settings = PlaybackSettings(updateRate = 20)
        val baked = PathBaker.bake(cutscene, settings, CatmullRomPathInterpolator(), worlds)
        assertEquals(2000 / 50 + 1, baked.size)

        val fast = PathBaker.bake(cutscene, PlaybackSettings(updateRate = 60), CatmullRomPathInterpolator(), worlds)
        assertEquals((2000 / PlaybackSettings(updateRate = 60).updateIntervalMs).toInt() + 1, fast.size)
    }

    @Test
    fun `starts and ends exactly on the first and last keyframe`() {
        interpolators.forEach { interp ->
            val baked = PathBaker.bake(cutscene, PlaybackSettings(), interp, worlds)
            val first = cutscene.frames.first().location
            val last = cutscene.frames.last().location
            assertEquals(Location(overworld, first.x, first.y, first.z, first.yaw, first.pitch), baked.first())
            assertEquals(Location(overworld, last.x, last.y, last.z, last.yaw, last.pitch), baked.last())
        }
    }

    @Test
    fun `samples are evenly spaced in time along the curve`() {
        interpolators.forEach { interp ->
            val baked = PathBaker.bake(cutscene, PlaybackSettings(updateRate = 20), interp, worlds)
            val curve = interp.curve(cutscene)
            baked.forEachIndexed { i, location ->
                val point = curve.sample(cutscene.totalDurationMs * i / (baked.size - 1).toDouble())
                assertEquals(point.x, location.x, 1e-9)
                assertEquals(point.z, location.z, 1e-9)
            }
        }
    }

    @Test
    fun `multi-world paths resolve each sample in its own world`() {
        val travel = Cutscene("travel", listOf(key(0.0, 0.0, 1000), key(5.0, 0.0, 1000, "world_nether"), key(9.0, 0.0, world = "world_nether")))
        val baked = PathBaker.bake(travel, PlaybackSettings(updateRate = 20), CatmullRomPathInterpolator(), worlds)
        assertSame(overworld, baked.first().world)
        assertSame(nether, baked.last().world)
        assertEquals(setOf(overworld, nether), baked.map { it.world }.toSet())
    }

    @Test
    fun `a world that is not loaded fails the bake`() {
        val lost = Cutscene("lost", listOf(key(0.0, 0.0, 1000, "world_the_end"), key(5.0, 0.0, world = "world_the_end")))
        assertThrows<IllegalStateException> {
            PathBaker.bake(lost, PlaybackSettings(), CatmullRomPathInterpolator(), worlds)
        }
    }

    @Test
    fun `cutscenes without a path bake to nothing`() {
        val settings = PlaybackSettings()
        val interp = CatmullRomPathInterpolator()
        assertTrue(PathBaker.bake(cutscene.copy(frames = emptyList()), settings, interp, worlds).isEmpty())
        assertTrue(PathBaker.bake(cutscene.copy(frames = cutscene.frames.take(1)), settings, interp, worlds).isEmpty())
    }
}
