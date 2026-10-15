package com.nonxedy.interpolator

import com.nonxedy.model.Cutscene
import com.nonxedy.model.CutsceneFrame
import org.bukkit.Location
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt

class TimedPathCurveTest {

    private val interpolators = listOf(CatmullRomPathInterpolator(), BezierPathInterpolator())

    private fun key(x: Double, y: Double, z: Double, segmentMs: Long? = null, yaw: Float = 0f) =
        CutsceneFrame(Location(null, x, y, z, yaw, 0f), "world", segmentMs)

    // a short fast hop, then a long slow stretch, then a turn
    private val cutscene = Cutscene(
        "test",
        listOf(
            key(0.0, 64.0, 0.0, 500),
            key(10.0, 64.0, 0.0, 8000),
            key(20.0, 70.0, 5.0, 2000),
            key(20.0, 70.0, 20.0, 1500),
            key(30.0, 66.0, 25.0)
        )
    )

    @Test
    fun `knot times follow per-segment durations`() {
        assertArrayEquals(longArrayOf(0, 500, 8500, 10500, 12000), cutscene.knotTimesMs())
        assertEquals(12000L, cutscene.totalDurationMs)
    }

    @Test
    fun `curve passes every keyframe exactly at its time`() {
        val knots = cutscene.knotTimesMs()
        for (interp in interpolators) {
            val curve = interp.curve(cutscene)
            cutscene.frames.forEachIndexed { i, frame ->
                val p = curve.sample(knots[i].toDouble())
                assertEquals(frame.location.x, p.x, 1e-9, "${interp::class.simpleName} key $i")
                assertEquals(frame.location.y, p.y, 1e-9, "${interp::class.simpleName} key $i")
                assertEquals(frame.location.z, p.z, 1e-9, "${interp::class.simpleName} key $i")
            }
        }
    }

    @Test
    fun `velocity is continuous and non-zero through interior keyframes`() {
        val knots = cutscene.knotTimesMs()
        val h = 0.01
        for (interp in interpolators) {
            val curve = interp.curve(cutscene)
            for (i in 1 until knots.size - 1) {
                val t = knots[i].toDouble()
                val before = velocity(curve, t - 2 * h, t - h)
                val after = velocity(curve, t + h, t + 2 * h)
                for (axis in 0..2) {
                    assertEquals(before[axis], after[axis], 1e-3, "${interp::class.simpleName} key $i axis $axis")
                }
                // Catmull-Rom never stops at an interior keyframe
                if (interp is CatmullRomPathInterpolator) {
                    assertTrue(norm(after) > 1e-4, "camera stopped at keyframe $i")
                }
            }
        }
    }

    @Test
    fun `camera eases in and out at the very ends`() {
        for (interp in interpolators) {
            val curve = interp.curve(cutscene)
            assertTrue(norm(velocity(curve, 0.0, 0.01)) < 1e-3)
            val end = cutscene.totalDurationMs.toDouble()
            assertTrue(norm(velocity(curve, end - 0.01, end)) < 1e-3)
        }
    }

    @Test
    fun `slower segment takes longer to cover the same ground`() {
        val fast = Cutscene("a", listOf(key(0.0, 0.0, 0.0, 1000), key(10.0, 0.0, 0.0, 1000), key(20.0, 0.0, 0.0)))
        val slow = fast.copy(frames = listOf(key(0.0, 0.0, 0.0, 1000), key(10.0, 0.0, 0.0, 4000), key(20.0, 0.0, 0.0)))
        val curveFast = CatmullRomPathInterpolator().curve(fast)
        val curveSlow = CatmullRomPathInterpolator().curve(slow)
        assertEquals(20.0, curveFast.sample(2000.0).x, 1e-9)
        assertEquals(20.0, curveSlow.sample(5000.0).x, 1e-9)
        assertTrue(curveSlow.sample(2000.0).x < 20.0)
    }

    @Test
    fun `bezier never swings past a keyframe on any axis`() {
        val curve = BezierPathInterpolator().curve(cutscene)
        val knots = cutscene.knotTimesMs()
        for (i in 0 until knots.size - 1) {
            val a = cutscene.frames[i].location
            val b = cutscene.frames[i + 1].location
            var t = knots[i].toDouble()
            while (t <= knots[i + 1]) {
                val p = curve.sample(t)
                assertTrue(p.x in minOf(a.x, b.x) - 1e-9..maxOf(a.x, b.x) + 1e-9)
                assertTrue(p.y in minOf(a.y, b.y) - 1e-9..maxOf(a.y, b.y) + 1e-9)
                assertTrue(p.z in minOf(a.z, b.z) - 1e-9..maxOf(a.z, b.z) + 1e-9)
                t += 25.0
            }
        }
    }

    @Test
    fun `yaw turns the short way across the wrap`() {
        val c = Cutscene("yaw", listOf(key(0.0, 0.0, 0.0, 1000, yaw = 170f), key(1.0, 0.0, 0.0, yaw = -170f)))
        val mid = CatmullRomPathInterpolator().curve(c).sample(500.0)
        assertEquals(180f, abs(mid.yaw), 0.01f)
    }

    private fun velocity(curve: PathCurve, t0: Double, t1: Double): DoubleArray {
        val a = curve.sample(t0)
        val b = curve.sample(t1)
        val dt = t1 - t0
        return doubleArrayOf((b.x - a.x) / dt, (b.y - a.y) / dt, (b.z - a.z) / dt)
    }

    private fun norm(v: DoubleArray) = sqrt(v.sumOf { it * it })
}
