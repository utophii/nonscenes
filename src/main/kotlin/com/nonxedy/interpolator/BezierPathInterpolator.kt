package com.nonxedy.interpolator

import com.nonxedy.model.CutsceneFrame
import com.nonxedy.model.playback.PathPoint
import org.bukkit.Location
import org.bukkit.util.Vector

class BezierPathInterpolator : PathInterpolator {

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

        val i1 = index.coerceIn(0, frames.lastIndex)
        val i2 = (index + 1).coerceIn(0, frames.lastIndex)

        val p1 = frames[i1].location
        val p2 = frames[i2].location

        if (frames[i1].worldName != frames[i2].worldName) {
            return if (localT < 1f) frames[i1].toPathPoint(0L) else frames[i2].toPathPoint(0L)
        }

        val c1 = controlPoint(frames, i1, forward = true)
        val c2 = controlPoint(frames, i2, forward = false)

        val pos = cubicBezier(
            Vector(p1.x, p1.y, p1.z),
            c1, c2,
            Vector(p2.x, p2.y, p2.z),
            localT
        )

        val yaw = lerpAngle(p1.yaw, p2.yaw, localT)
        val pitch = lerpAngle(p1.pitch, p2.pitch, localT)

        return PathPoint(
            x = pos.x,
            y = pos.y,
            z = pos.z,
            yaw = yaw,
            pitch = pitch,
            worldName = frames[i1].worldName,
            timeMs = 0L
        )
    }

    private fun controlPoint(frames: List<CutsceneFrame>, index: Int, forward: Boolean): Vector {
        val prev = frames.getOrNull(index - 1)?.location
        val curr = frames[index].location
        val next = frames.getOrNull(index + 1)?.location

        val dir = when {
            prev != null && next != null -> Vector(next.x - prev.x, next.y - prev.y, next.z - prev.z).multiply(0.25)
            next != null -> Vector(next.x - curr.x, next.y - curr.y, next.z - curr.z).multiply(0.5)
            prev != null -> Vector(curr.x - prev.x, curr.y - prev.y, curr.z - prev.z).multiply(0.5)
            else -> Vector(0, 0, 0)
        }

        return if (forward) Vector(curr.x, curr.y, curr.z).add(dir)
        else Vector(curr.x, curr.y, curr.z).subtract(dir)
    }

    private fun cubicBezier(p0: Vector, p1: Vector, p2: Vector, p3: Vector, t: Float): Vector {
        val u = 1f - t
        val tt = t * t
        val uu = u * u
        val uuu = uu * u
        val ttt = tt * t

        var p = p0.clone().multiply(uuu.toDouble())
        p.add(p1.clone().multiply((3 * uu * t).toDouble()))
        p.add(p2.clone().multiply((3 * u * tt).toDouble()))
        p.add(p3.clone().multiply(ttt.toDouble()))
        return p
    }

    private fun lerpAngle(from: Float, to: Float, t: Float): Float {
        var delta = (to - from) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return from + delta * t
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
