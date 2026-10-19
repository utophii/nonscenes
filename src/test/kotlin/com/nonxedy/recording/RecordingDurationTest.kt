package com.nonxedy.recording

import com.nonxedy.model.Cutscene
import com.nonxedy.model.CutsceneFrame
import com.nonxedy.model.recording.RecordingSettings
import org.bukkit.Location
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RecordingDurationTest {

    // drives the schedule the way the recorder's task does, one call per run
    private fun record(intervalTicks: Int, seconds: Int, tickMs: Long = intervalTicks * 50L): Pair<Int, CaptureSchedule> {
        val schedule = CaptureSchedule(intervalTicks, seconds, startedAtMs = 0L)
        var frames = 0
        var now = 0L
        while (true) {
            frames += schedule.framesDue(now)
            if (schedule.isFinished(now)) return frames to schedule
            now += tickMs
        }
    }

    private fun playbackMs(frames: Int, schedule: CaptureSchedule): Long {
        val recorded = List(frames) { CutsceneFrame(Location(null, 0.0, 0.0, 0.0), "world") }
        return Cutscene("rec", recorded, schedule.frameDurationMs).totalDurationMs
    }

    @Test
    fun `duration must be between 1 and 300 seconds`() {
        assertTrue(RecordingSettings.isValidDuration(1))
        assertTrue(RecordingSettings.isValidDuration(RecordingSettings.MAX_DURATION_SECONDS))
        assertFalse(RecordingSettings.isValidDuration(0))
        assertFalse(RecordingSettings.isValidDuration(-5))
        assertFalse(RecordingSettings.isValidDuration(RecordingSettings.MAX_DURATION_SECONDS + 1))
        assertFalse(RecordingSettings.isValidDuration(null))
    }

    @Test
    fun `frame duration follows the capture interval`() {
        assertEquals(50L, CaptureSchedule(1, 5, 0L).frameDurationMs)
        assertEquals(100L, CaptureSchedule(2, 5, 0L).frameDurationMs)
        assertEquals(50L, CaptureSchedule(0, 5, 0L).frameDurationMs)
    }

    @Test
    fun `recording plays back for exactly the requested duration`() {
        for (interval in listOf(1, 2, 4)) {
            val (frames, schedule) = record(interval, 5)
            assertEquals(5000L / schedule.frameDurationMs + 1, frames.toLong(), "interval $interval")
            assertEquals(5000L, playbackMs(frames, schedule), "interval $interval")
        }
    }

    @Test
    fun `a lagging server catches up without recording extra frames`() {
        val (onTime, _) = record(1, 5)
        val (lagged, schedule) = record(1, 5, tickMs = 230L)
        assertEquals(onTime, lagged)
        assertEquals(5000L, playbackMs(lagged, schedule))
    }

    @Test
    fun `nothing is captured after the end`() {
        val schedule = CaptureSchedule(1, 1, startedAtMs = 1000L)
        assertEquals(0, schedule.framesDue(999L))
        assertEquals(21, schedule.framesDue(5000L))
        assertEquals(0, schedule.framesDue(9000L))
        assertTrue(schedule.isFinished(2000L))
        assertFalse(schedule.isFinished(1999L))
        assertEquals(0.5, schedule.progress(1500L), 1e-9)
    }
}
