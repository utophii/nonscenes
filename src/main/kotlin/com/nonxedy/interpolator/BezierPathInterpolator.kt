package com.nonxedy.interpolator

import com.nonxedy.model.CutsceneFrame

// Cubic Bezier whose control points come from monotone (PCHIP) velocities: on
// every axis the curve stays between neighbouring keyframes.
// Tighter than Catmull-Rom, with sharper corners
class BezierPathInterpolator : PathInterpolator {

    override fun curve(frames: List<CutsceneFrame>, knotsMs: LongArray): PathCurve =
        PathCurve(frames, knotsMs, RULE)

    private companion object {
        val RULE = TangentRule { prev, curr, next, dt0, dt1 ->
            val d0 = (curr - prev) / dt0
            val d1 = (next - curr) / dt1
            if (d0 * d1 <= 0.0) {
                0.0
            } else {
                // weighted harmonic mean (Fritsch-Butland)
                val w0 = 2 * dt1 + dt0
                val w1 = dt1 + 2 * dt0
                (w0 + w1) / (w0 / d0 + w1 / d1)
            }
        }
    }
}
