package com.nonxedy.interpolator

import com.nonxedy.model.CutsceneFrame

// non-uniform Catmull-Rom: the velocity at a keyframe is the average velocity
// from the previous keyframe to the next one. Round, flowing curves
class CatmullRomPathInterpolator : PathInterpolator {

    override fun curve(frames: List<CutsceneFrame>, knotsMs: LongArray): PathCurve =
        PathCurve(frames, knotsMs, RULE)

    private companion object {
        val RULE = TangentRule { prev, _, next, dt0, dt1 -> (next - prev) / (dt0 + dt1) }
    }
}
