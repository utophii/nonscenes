package com.nonxedy.playback

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin

// stores what a cutscene changed on the player in their PDC
class PlaybackRecovery(plugin: JavaPlugin) {

    private val key = NamespacedKey(plugin, "playback_return")

    fun save(player: Player, returnTo: Location, wasFlying: Boolean, wasAllowedFlight: Boolean) {
        val world = returnTo.world ?: return
        val value = listOf(
            world.name, returnTo.x, returnTo.y, returnTo.z, returnTo.yaw, returnTo.pitch,
            wasFlying, wasAllowedFlight
        ).joinToString(SEPARATOR)
        player.persistentDataContainer.set(key, PersistentDataType.STRING, value)
    }

    fun clear(player: Player) {
        player.persistentDataContainer.remove(key)
    }

    // returns true if a leftover state was found and restored
    fun restore(player: Player): Boolean {
        val raw = player.persistentDataContainer.get(key, PersistentDataType.STRING) ?: return false
        clear(player)

        val parts = raw.split(SEPARATOR)
        if (parts.size != 8) return false
        val world = Bukkit.getWorld(parts[0])
        val wasFlying = parts[6].toBoolean()
        val wasAllowedFlight = parts[7].toBoolean()

        player.setAllowFlight(wasAllowedFlight)
        player.setFlying(wasFlying && wasAllowedFlight)
        if (world != null) {
            val loc = runCatching {
                Location(world, parts[1].toDouble(), parts[2].toDouble(), parts[3].toDouble(),
                    parts[4].toFloat(), parts[5].toFloat())
            }.getOrNull()
            if (loc != null) player.teleportAsync(loc, PlayerTeleportEvent.TeleportCause.PLUGIN)
        }
        return true
    }

    private companion object {
        const val SEPARATOR = ";"
    }
}
