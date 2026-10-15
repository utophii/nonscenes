package com.nonxedy.model

import org.bukkit.Bukkit
import org.bukkit.Location

// represents a single frame in a cutscene.
// segmentMs is how long the camera takes to reach the next frame. null means the
// cutscene's frameDurationMs, which is what raw recordings use
data class CutsceneFrame(
    val location: Location,
    val worldName: String = location.world?.name.orEmpty(),
    val segmentMs: Long? = null
) {
    fun resolveLocation(): Location? {
        val world = location.world ?: Bukkit.getWorld(worldName) ?: return null
        return Location(world, location.x, location.y, location.z, location.yaw, location.pitch)
    }
}
