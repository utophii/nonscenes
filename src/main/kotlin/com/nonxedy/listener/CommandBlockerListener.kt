package com.nonxedy.listener

import com.nonxedy.Nonscenes
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerCommandSendEvent

class CommandBlockerListener(private val plugin: Nonscenes) : Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerCommand(event: PlayerCommandPreprocessEvent) {
        blockUnlessStop(event.player, event.message) { event.isCancelled = true }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onCommandSend(event: PlayerCommandSendEvent) {
        if (!plugin.cutsceneManager.isWatchingCutscene(event.player)) return
        event.commands.removeIf { it != "nonscene" && it != "ns" }
    }

    private fun blockUnlessStop(player: Player, message: String, cancel: () -> Unit) {
        if (!plugin.cutsceneManager.isWatchingCutscene(player)) return
        if (PlaybackCommandPolicy.isAllowedDuringPlayback(message)) return
        cancel()
        player.sendMessage(plugin.configManager.getMessage("command-disabled-during-cutscene"))
    }
}
