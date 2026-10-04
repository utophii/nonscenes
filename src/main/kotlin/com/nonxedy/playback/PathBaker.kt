package com.nonxedy.playback

import com.nonxedy.interpolator.PathInterpolator
import com.nonxedy.model.Cutscene
import com.nonxedy.model.playback.PlaybackSettings
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World

object PathBaker {

    fun bake(cutscene: Cutscene, settings: PlaybackSettings, interpolator: PathInterpolator): List<Location> {
        val totalDurationMs = cutscene.frameDurationMs * (cutscene.frames.size - 1)
        if (totalDurationMs <= 0 || cutscene.frames.isEmpty()) return emptyList()

        val frameCount = ((totalDurationMs / settings.updateIntervalMs.toDouble()).toInt() + 1)
            .coerceAtLeast(2)

        val worlds = HashMap<String, World>()
        fun world(name: String): World {
            return worlds.getOrPut(name) {
                Bukkit.getWorld(name) ?: throw IllegalStateException("World '$name' is not loaded")
            }
        }

        return (0 until frameCount).map { i ->
            val t = if (i == frameCount - 1) 1.0 else i.toDouble() / (frameCount - 1)
            val point = interpolator.interpolate(cutscene.frames, t)
            Location(world(point.worldName), point.x, point.y, point.z, point.yaw, point.pitch)
        }
    }
}
