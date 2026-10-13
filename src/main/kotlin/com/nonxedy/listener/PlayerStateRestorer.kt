package com.nonxedy.listener

import com.nonxedy.Nonscenes
import com.nonxedy.playback.PlaybackRecovery
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

class PlayerStateRestorer(private val plugin: Nonscenes) : Listener {

    private val recovery = PlaybackRecovery(plugin)

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player

        // Cancel any active session so recording/showpath tasks do not leak after disconnect.
        if (plugin.cutsceneManager.hasActiveSession(player)) {
            plugin.cutsceneManager.cancelAllSessions(player)
        }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        // the server stopped mid-cutscene: put the player back where the cutscene found them
        recovery.restore(event.player)
    }
}
