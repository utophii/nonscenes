package com.nonxedy.interpolator

import com.nonxedy.model.CutsceneFrame
import com.nonxedy.model.playback.PathPoint

// Cubic Hermite spline through every frame, parameterised by time: frame i is
// passed exactly at knotsMs[i]. Tangents are velocities (units per ms) shared by
// the two segments meeting at a knot, so the camera never stops at a keyframe and
// changing one segment's duration does not break the motion around it.
// The camera eases in at the very start and out at the very end, and around a
// world change, where the path is cut.
class PathCurve internal constructor(
    private val frames: List<CutsceneFrame>,
    knotsMs: LongArray,
    tangentRule: TangentRule
) {
    private val n = frames.size
    private val knots = DoubleArray(n) { knotsMs[it].toDouble() }
    private val worlds = Array(n) { frames[it].worldName }

    // x, y, z, yaw (unwrapped so it never spins the long way round), pitch
    private val values: Array<DoubleArray> = Array(CHANNELS) { DoubleArray(n) }
    private val tangents: Array<DoubleArray> = Array(CHANNELS) { DoubleArray(n) }

    init {
        require(n > 0) { "Frame list must not be empty" }
        require(knotsMs.size == n) { "Need one knot per frame" }
        for (i in 1 until n) {
            require(knotsMs[i] > knotsMs[i - 1]) { "Knot times must strictly increase" }
        }

        for (i in 0 until n) {
            val loc = frames[i].location
            values[X][i] = loc.x
            values[Y][i] = loc.y
            values[Z][i] = loc.z
            values[YAW][i] = if (i == 0) loc.yaw.toDouble()
            else values[YAW][i - 1] + shortestDelta(frames[i - 1].location.yaw, loc.yaw)
            values[PITCH][i] = loc.pitch.toDouble()
        }

        for (i in 0 until n) {
            val linkedBack = i > 0 && worlds[i - 1] == worlds[i]
            val linkedForward = i < n - 1 && worlds[i + 1] == worlds[i]
            if (!linkedBack || !linkedForward) continue // ends of a run stay at zero velocity
            val dt0 = knots[i] - knots[i - 1]
            val dt1 = knots[i + 1] - knots[i]
            for (c in 0 until CHANNELS) {
                val v = values[c]
                tangents[c][i] = tangentRule.tangent(v[i - 1], v[i], v[i + 1], dt0, dt1)
            }
        }
    }

    val durationMs: Double
        get() = knots.last()

    fun sample(timeMs: Double): PathPoint {
        if (n == 1 || timeMs <= knots[0]) return point(0)
        if (timeMs >= knots[n - 1]) return point(n - 1)

        val i = segmentAt(timeMs)
        // the camera holds the last frame of a world, then cuts to the next one
        if (worlds[i] != worlds[i + 1]) return point(i)

        val h = knots[i + 1] - knots[i]
        val s = (timeMs - knots[i]) / h
        val s2 = s * s
        val s3 = s2 * s
        val h00 = 2 * s3 - 3 * s2 + 1
        val h10 = s3 - 2 * s2 + s
        val h01 = -2 * s3 + 3 * s2
        val h11 = s3 - s2

        fun channel(c: Int): Double {
            val v = values[c]
            val m = tangents[c]
            return h00 * v[i] + h10 * h * m[i] + h01 * v[i + 1] + h11 * h * m[i + 1]
        }

        return PathPoint(
            x = channel(X),
            y = channel(Y),
            z = channel(Z),
            yaw = normalizeYaw(channel(YAW)),
            pitch = channel(PITCH).coerceIn(-90.0, 90.0).toFloat(),
            worldName = worlds[i],
            timeMs = timeMs.toLong()
        )
    }

    // index of the segment containing timeMs, in 0..n-2
    private fun segmentAt(timeMs: Double): Int {
        var lo = 0
        var hi = n - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (knots[mid] <= timeMs) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun point(i: Int): PathPoint {
        val loc = frames[i].location
        return PathPoint(loc.x, loc.y, loc.z, loc.yaw, loc.pitch, worlds[i], knots[i].toLong())
    }

    private companion object {
        const val X = 0
        const val Y = 1
        const val Z = 2
        const val YAW = 3
        const val PITCH = 4
        const val CHANNELS = 5

        fun shortestDelta(from: Float, to: Float): Double {
            var d = ((to - from) % 360.0 + 360.0) % 360.0
            if (d > 180.0) d -= 360.0
            return d
        }

        fun normalizeYaw(yaw: Double): Float {
            var y = (yaw % 360.0 + 360.0) % 360.0
            if (y >= 180.0) y -= 360.0
            return y.toFloat()
        }
    }
}

// picks the velocity at an interior knot from its two neighbours.
// dt0 / dt1 are the durations of the segments before and after it
internal fun interface TangentRule {
    fun tangent(prev: Double, curr: Double, next: Double, dt0: Double, dt1: Double): Double
}
