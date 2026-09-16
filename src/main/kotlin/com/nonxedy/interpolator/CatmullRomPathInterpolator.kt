package com.nonxedy.interpolator

import com.nonxedy.model.CutsceneFrame
import com.nonxedy.model.playback.PathPoint

class CatmullRomPathInterpolator : PathInterpolator {

    override fun interpolate(frames: List<CutsceneFrame>, t: Double): PathPoint {
        require(frames.isNotEmpty()) { "Frame list must not be empty" }
        if (frames.size == 1 || t <= 0.0) {
            return frames.first().toPathPoint(0L)
        }
        if (t >= 1.0) {
            return frames.last().toPathPoint(0L)
        }

        val segmentCount = frames.size - 1
        val scaledT = t * segmentCount
        val index = scaledT.toInt()
        val localT = (scaledT - index).toFloat()

        val n = frames.size
        val i0 = (index - 1).coerceAtLeast(0)
        val i1 = index.coerceIn(0, n - 1)
        val i2 = (index + 1).coerceAtMost(n - 1)
        val i3 = (index + 2).coerceAtMost(n - 1)

        val p0 = frames[i0].location
        val p1 = frames[i1].location
        val p2 = frames[i2].location
        val p3 = frames[i3].location

        if (frames[i1].worldName != frames[i2].worldName) {
            return if (localT < 1f) frames[i1].toPathPoint(0L) else frames[i2].toPathPoint(0L)
        }

        val worldName = frames[i1].worldName

        val easedT = smoothStep(localT)

        val x = catmullRom(p0.x, p1.x, p2.x, p3.x, easedT)
        val y = catmullRom(p0.y, p1.y, p2.y, p3.y, easedT)
        val z = catmullRom(p0.z, p1.z, p2.z, p3.z, easedT)
        val yaw = catmullRomAngle(p0.yaw, p1.yaw, p2.yaw, p3.yaw, easedT)
        val pitch = catmullRomAngle(p0.pitch, p1.pitch, p2.pitch, p3.pitch, easedT)

        return PathPoint(
            x = x,
            y = y,
            z = z,
            yaw = yaw,
            pitch = pitch,
            worldName = worldName,
            timeMs = 0L
        )
    }

    private fun smoothStep(t: Float): Float = t * t * (3f - 2f * t)

    private fun catmullRom(p0: Double, p1: Double, p2: Double, p3: Double, t: Float): Double {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5 * (
            (2.0 * p1) +
            (-p0 + p2) * t +
            (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2 +
            (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3
        )
    }

    private fun catmullRomAngle(a0: Float, a1: Float, a2: Float, a3: Float, t: Float): Float {
        fun norm(a: Float) = ((a % 360f) + 360f) % 360f
        fun diff(from: Float, to: Float): Float {
            var d = norm(to) - norm(from)
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            return d
        }
        val b0 = a1 - diff(a0, a1)
        val b2 = a1 + diff(a1, a2)
        val b3 = b2 + diff(a2, a3)
        return catmullRom(b0.toDouble(), a1.toDouble(), b2.toDouble(), b3.toDouble(), t).toFloat()
    }
}

private fun CutsceneFrame.toPathPoint(timeMs: Long): PathPoint = PathPoint(
    x = location.x,
    y = location.y,
    z = location.z,
    yaw = location.yaw,
    pitch = location.pitch,
    worldName = worldName,
    timeMs = timeMs
)
