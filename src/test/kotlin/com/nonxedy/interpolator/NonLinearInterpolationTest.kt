package com.nonxedy.interpolator

import com.nonxedy.model.Cutscene
import com.nonxedy.model.CutsceneFrame
import com.nonxedy.model.playback.InterpolationType
import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.InputStreamReader
import kotlin.math.hypot

class NonLinearInterpolationTest {

    @Test
    fun `only spline interpolation types exist`() {
        assertEquals(setOf("CATMULL_ROM", "BEZIER"), InterpolationType.entries.map { it.name }.toSet())
    }

    @Test
    fun `linear and unknown config values fall back to catmull-rom`() {
        assertEquals(InterpolationType.CATMULL_ROM, InterpolationType.parse("LINEAR"))
        assertEquals(InterpolationType.CATMULL_ROM, InterpolationType.parse("linear"))
        assertEquals(InterpolationType.CATMULL_ROM, InterpolationType.parse("nonsense"))
        assertEquals(InterpolationType.CATMULL_ROM, InterpolationType.parse(null))
        assertEquals(InterpolationType.BEZIER, InterpolationType.parse(" bezier "))
    }

    @Test
    fun `bundled config uses a spline`() {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream("config.yml"))
        val config = YamlConfiguration.loadConfiguration(InputStreamReader(stream, Charsets.UTF_8))
        val value = config.getString("cutscene.playback.interpolation")
        assertTrue(InterpolationType.entries.any { it.name == value }, "unexpected interpolation '$value'")
    }

    // a linear path would sit on the straight line between keyframes, at a share of it
    // matching the share of the segment's time. Both splines must leave that line.
    // (The path keeps heading the same way: at a hard corner BEZIER's monotone
    // tangents are zero and each leg is straight in space.)
    @Test
    fun `camera does not move along straight lines at constant speed`() {
        val key = { x: Double, z: Double, segmentMs: Long? ->
            CutsceneFrame(Location(null, x, 64.0, z), "world", segmentMs)
        }
        val path = Cutscene("bend", listOf(key(0.0, 0.0, 1000), key(10.0, 2.0, 1000), key(20.0, 12.0, 1000), key(25.0, 30.0, null)))

        listOf(CatmullRomPathInterpolator(), BezierPathInterpolator()).forEach { interp ->
            val curve = interp.curve(path)
            val from = path.frames[1].location
            val to = path.frames[2].location
            listOf(0.25, 0.5, 0.75).forEach { share ->
                val point = curve.sample(1000.0 + 1000.0 * share)
                val linearX = from.x + (to.x - from.x) * share
                val linearZ = from.z + (to.z - from.z) * share
                assertTrue(
                    hypot(point.x - linearX, point.z - linearZ) > 0.05,
                    "${interp::class.simpleName} is linear at $share of the middle segment"
                )
            }
        }
    }
}
