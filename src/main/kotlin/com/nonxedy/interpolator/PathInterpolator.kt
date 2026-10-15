package com.nonxedy.interpolator

import com.nonxedy.model.Cutscene
import com.nonxedy.model.CutsceneFrame
import com.nonxedy.model.playback.PathPoint

interface PathInterpolator {
    // a curve that passes frame i exactly at knotsMs[i]
    fun curve(frames: List<CutsceneFrame>, knotsMs: LongArray): PathCurve

    fun curve(cutscene: Cutscene): PathCurve = curve(cutscene.frames, cutscene.knotTimesMs())

    // frames spaced evenly over t in 0..1
    fun interpolate(frames: List<CutsceneFrame>, t: Double): PathPoint {
        val curve = curve(frames, LongArray(frames.size) { it.toLong() })
        return curve.sample(t * frames.lastIndex)
    }
}
