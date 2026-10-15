package com.nonxedy.editor

import com.nonxedy.model.Cutscene
import com.nonxedy.model.CutsceneFrame
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

// turns a dense recording into keyframes.
// The error of a dropped frame is measured against where the camera would be at
// that frame's time, not just against the line, so changes in speed survive too.
// Every keyframe keeps its original time
object KeyframeSimplifier {

    fun simplify(cutscene: Cutscene, positionTolerance: Double, angleTolerance: Double): List<CutsceneFrame> {
        val frames = cutscene.frames
        val n = frames.size
        val times = cutscene.knotTimesMs()
        val keep = BooleanArray(n)
        if (n > 0) {
            keep[0] = true
            keep[n - 1] = true
        }
        // a world change is a cut, both sides of it stay
        for (i in 0 until n - 1) {
            if (frames[i].worldName != frames[i + 1].worldName) {
                keep[i] = true
                keep[i + 1] = true
            }
        }

        val pending = ArrayDeque<Pair<Int, Int>>()
        var last = 0
        for (i in 1 until n) {
            if (keep[i]) {
                pending.addLast(last to i)
                last = i
            }
        }

        while (pending.isNotEmpty()) {
            val (a, b) = pending.removeLast()
            if (b - a < 2) continue
            var worst = -1
            var worstError = 1.0
            for (i in a + 1 until b) {
                val error = error(frames, times, a, b, i, positionTolerance, angleTolerance)
                if (error > worstError) {
                    worstError = error
                    worst = i
                }
            }
            if (worst >= 0) {
                keep[worst] = true
                pending.addLast(a to worst)
                pending.addLast(worst to b)
            }
        }

        val kept = (0 until n).filter { keep[it] }
        return kept.mapIndexed { k, index ->
            val next = kept.getOrNull(k + 1)
            frames[index].copy(segmentMs = next?.let { times[it] - times[index] })
        }
    }

    // how far frame i is from the straight, constant-speed move a -> b at the same
    // moment, relative to the tolerances (> 1 means it has to stay)
    private fun error(
        frames: List<CutsceneFrame>,
        times: LongArray,
        a: Int,
        b: Int,
        i: Int,
        positionTolerance: Double,
        angleTolerance: Double
    ): Double {
        val u = (times[i] - times[a]).toDouble() / (times[b] - times[a]).toDouble()
        val pa = frames[a].location
        val pb = frames[b].location
        val p = frames[i].location

        val dx = pa.x + (pb.x - pa.x) * u - p.x
        val dy = pa.y + (pb.y - pa.y) * u - p.y
        val dz = pa.z + (pb.z - pa.z) * u - p.z
        val positionError = sqrt(dx * dx + dy * dy + dz * dz)

        val yaw = pa.yaw + angleDelta(pa.yaw.toDouble(), pb.yaw.toDouble()) * u
        val yawError = abs(angleDelta(yaw, p.yaw.toDouble()))
        val pitchError = abs(pa.pitch + (pb.pitch - pa.pitch) * u - p.pitch)

        return max(positionError / positionTolerance, max(yawError, pitchError) / angleTolerance)
    }

    private fun angleDelta(from: Double, to: Double): Double {
        var d = ((to - from) % 360.0 + 360.0) % 360.0
        if (d > 180.0) d -= 360.0
        return d
    }
}
