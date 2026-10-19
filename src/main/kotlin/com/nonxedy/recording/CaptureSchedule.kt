package com.nonxedy.recording

// when frames are due during a recording, the last frame lands on the end time
class CaptureSchedule(captureIntervalTicks: Int, durationSeconds: Int, private val startedAtMs: Long) {
    val frameDurationMs: Long = (1000.0 / (20.0 / captureIntervalTicks.coerceAtLeast(1))).toLong().coerceAtLeast(1L)
    val durationMs: Long = durationSeconds * 1000L
    private val endMs = startedAtMs + durationMs
    private var nextFrameAtMs = startedAtMs

    // frames to capture now, more than one when the server lagged behind
    fun framesDue(nowMs: Long): Int {
        var due = 0
        while (nowMs >= nextFrameAtMs && nextFrameAtMs <= endMs) {
            due++
            nextFrameAtMs += frameDurationMs
        }
        return due
    }

    fun isFinished(nowMs: Long): Boolean = nowMs >= endMs

    fun progress(nowMs: Long): Double = ((nowMs - startedAtMs).toDouble() / durationMs).coerceIn(0.0, 1.0)
}
