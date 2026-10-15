package com.nonxedy.editor

import com.nonxedy.model.Cutscene
import com.nonxedy.model.CutsceneFrame
import org.bukkit.Location
import kotlin.math.roundToLong

// mutable working copy of a cutscene's keyframes. Every keyframe except the last
// owns the duration of the segment that follows it
class KeyframeTrack(frames: List<CutsceneFrame>, private val defaultSegmentMs: Long) {

    private val keys: MutableList<CutsceneFrame> = frames.toMutableList()

    init {
        normalizeLast()
    }

    val size: Int
        get() = keys.size

    val totalMs: Long
        get() = (0 until keys.size - 1).sumOf { segmentMs(it) }

    fun frames(): List<CutsceneFrame> = keys.toList()

    operator fun get(index: Int): CutsceneFrame = keys[index]

    fun segmentMs(index: Int): Long = keys[index].segmentMs ?: defaultSegmentMs

    // time at which the camera passes keyframe `index`
    fun timeAt(index: Int): Long = (0 until index).sumOf { segmentMs(it) }

    // inserts a keyframe so that it becomes keyframe `index` (0..size)
    fun insert(index: Int, location: Location) {
        require(index in 0..keys.size) { "index out of range" }
        val frame = frameAt(location)
        when {
            keys.isEmpty() -> keys.add(frame)
            index == keys.size -> {
                val prevIndex = keys.lastIndex
                val duration = durationAtSpeedOf(prevIndex - 1, keys[prevIndex], frame)
                keys[prevIndex] = keys[prevIndex].copy(segmentMs = duration)
                keys.add(frame)
            }
            index == 0 -> {
                val duration = durationAtSpeedOf(0, frame, keys[0])
                keys.add(0, frame.copy(segmentMs = duration))
            }
            else -> {
                // split the segment it lands in, keeping its total time
                val prev = keys[index - 1]
                val next = keys[index]
                val total = segmentMs(index - 1)
                val before = distance(prev, frame)
                val after = distance(frame, next)
                val share = if (before.isNaN() || after.isNaN() || before + after <= 0.0) 0.5
                else before / (before + after)
                val first = clampSegment((total * share).roundToLong())
                val second = clampSegment(total - first)
                keys[index - 1] = prev.copy(segmentMs = first)
                keys.add(index, frame.copy(segmentMs = second))
            }
        }
        normalizeLast()
    }

    // removing a middle keyframe merges its two segments
    fun remove(index: Int) {
        require(index in keys.indices) { "index out of range" }
        if (index in 1 until keys.lastIndex) {
            val prev = keys[index - 1]
            keys[index - 1] = prev.copy(segmentMs = clampSegment(segmentMs(index - 1) + segmentMs(index)))
        }
        keys.removeAt(index)
        normalizeLast()
    }

    // moves a keyframe to a new position and view, its timing stays the same
    fun move(index: Int, location: Location) {
        require(index in keys.indices) { "index out of range" }
        keys[index] = frameAt(location).copy(segmentMs = keys[index].segmentMs)
    }

    // sets how long the camera takes from keyframe index to index + 1
    fun setSegment(index: Int, durationMs: Long) {
        require(index in 0 until keys.lastIndex) { "index out of range" }
        keys[index] = keys[index].copy(segmentMs = clampSegment(durationMs))
    }

    fun toCutscene(name: String, base: Cutscene?): Cutscene = Cutscene(
        name = name,
        frames = frames(),
        frameDurationMs = base?.frameDurationMs ?: Cutscene.MIN_SEGMENT_MS,
        events = base?.events ?: emptyList()
    )

    // time for from -> to at the speed of the segment speedOf, or the default
    private fun durationAtSpeedOf(speedOf: Int, from: CutsceneFrame, to: CutsceneFrame): Long {
        val distance = distance(from, to)
        if (speedOf !in 0 until keys.lastIndex || distance.isNaN() || distance <= 0.0) {
            return clampSegment(defaultSegmentMs)
        }
        val reference = distance(keys[speedOf], keys[speedOf + 1])
        if (reference.isNaN() || reference <= 0.0) return clampSegment(defaultSegmentMs)
        val blocksPerMs = reference / segmentMs(speedOf)
        return clampSegment((distance / blocksPerMs).roundToLong())
    }

    private fun normalizeLast() {
        if (keys.isNotEmpty() && keys.last().segmentMs != null) {
            keys[keys.lastIndex] = keys.last().copy(segmentMs = null)
        }
        for (i in 0 until keys.lastIndex) {
            if (keys[i].segmentMs == null) keys[i] = keys[i].copy(segmentMs = clampSegment(defaultSegmentMs))
        }
    }

    companion object {
        const val MAX_SEGMENT_MS: Long = 10 * 60 * 1000L

        fun clampSegment(ms: Long): Long = ms.coerceIn(Cutscene.MIN_SEGMENT_MS, MAX_SEGMENT_MS)

        // keyframes from an existing cutscene
        fun from(cutscene: Cutscene, defaultSegmentMs: Long, positionTolerance: Double, angleTolerance: Double): KeyframeTrack {
            val frames = if (cutscene.isRawRecording) {
                KeyframeSimplifier.simplify(cutscene, positionTolerance, angleTolerance)
            } else {
                cutscene.frames.mapIndexed { i, f ->
                    f.copy(segmentMs = if (i < cutscene.frames.lastIndex) cutscene.segmentMs(i) else null)
                }
            }
            return KeyframeTrack(frames, defaultSegmentMs)
        }

        private fun frameAt(location: Location): CutsceneFrame {
            val copy = location.clone()
            return CutsceneFrame(copy, copy.world?.name.orEmpty())
        }

        // NaN when the frames are in different worlds
        private fun distance(a: CutsceneFrame, b: CutsceneFrame): Double {
            if (a.worldName != b.worldName) return Double.NaN
            val dx = a.location.x - b.location.x
            val dy = a.location.y - b.location.y
            val dz = a.location.z - b.location.z
            return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        }
    }
}
