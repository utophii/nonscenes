package com.nonxedy.model

import org.bukkit.Bukkit
import org.bukkit.Location

// Represents a single frame in a cutscene
data class CutsceneFrame(
    val location: Location,
    val worldName: String = location.world?.name.orEmpty()
) {
    fun resolveLocation(): Location? {
        val world = location.world ?: Bukkit.getWorld(worldName) ?: return null
        return Location(world, location.x, location.y, location.z, location.yaw, location.pitch)
    }
}
