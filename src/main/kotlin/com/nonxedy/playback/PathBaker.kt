package com.nonxedy.playback

import com.nonxedy.interpolator.PathInterpolator
import com.nonxedy.model.Cutscene
import com.nonxedy.model.playback.PlaybackSettings
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World

object PathBaker {

    // samples the spline at evenly spaced times. The result is what the camera
    // shows at 0, interval, 2 * interval, ... up to the cutscene's total duration
    fun bake(cutscene: Cutscene, settings: PlaybackSettings, interpolator: PathInterpolator): List<Location> {
        if (cutscene.frames.isEmpty()) return emptyList()
        val totalDurationMs = cutscene.totalDurationMs
        if (totalDurationMs <= 0) return emptyList()

        val frameCount = ((totalDurationMs / settings.updateIntervalMs.toDouble()).toInt() + 1)
            .coerceAtLeast(2)
        val curve = interpolator.curve(cutscene)

        val worlds = HashMap<String, World>()
        fun world(name: String): World {
            return worlds.getOrPut(name) {
                Bukkit.getWorld(name) ?: throw IllegalStateException("World '$name' is not loaded")
            }
        }

        return (0 until frameCount).map { i ->
            val time = if (i == frameCount - 1) totalDurationMs.toDouble()
            else totalDurationMs * i.toDouble() / (frameCount - 1)
            val point = curve.sample(time)
            Location(world(point.worldName), point.x, point.y, point.z, point.yaw, point.pitch)
        }
    }
}
