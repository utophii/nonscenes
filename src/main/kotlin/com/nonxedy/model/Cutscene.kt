package com.nonxedy.model

import com.nonxedy.model.timeline.TimelineEvent
import kotlin.math.ceil

// Represents a cutscene with a name and a list of frames
data class Cutscene(
    val name: String,
    val frames: List<CutsceneFrame>,
    val frameDurationMs: Long = 50L,
    val events: List<TimelineEvent> = emptyList()
) {
    constructor(name: String, frames: List<CutsceneFrame>, ticksPerFrame: Int) : this(
        name,
        frames,
        ticksPerFrame.coerceAtLeast(1) * 50L
    )

    val ticksPerFrame: Int
        get() = ceil(frameDurationMs.coerceAtLeast(1L) / 50.0).toInt()

    // a raw recording has no per-segment timing yet
    val isRawRecording: Boolean
        get() = frames.all { it.segmentMs == null }

    // duration of the segment from frame index to frame index + 1
    fun segmentMs(index: Int): Long =
        (frames[index].segmentMs ?: frameDurationMs).coerceAtLeast(MIN_SEGMENT_MS)

    // time at which the camera passes each frame, starting at 0
    fun knotTimesMs(): LongArray {
        val knots = LongArray(frames.size)
        for (i in 1 until frames.size) knots[i] = knots[i - 1] + segmentMs(i - 1)
        return knots
    }

    val totalDurationMs: Long
        get() = if (frames.size < 2) 0L else knotTimesMs().last()

    companion object {
        const val MIN_SEGMENT_MS: Long = 50L
    }
}
