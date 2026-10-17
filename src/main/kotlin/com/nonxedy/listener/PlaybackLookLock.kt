package com.nonxedy.listener

import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatCommand
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatCommandUnsigned
import com.nonxedy.Nonscenes
import org.bukkit.Bukkit
import org.bukkit.entity.Player

class PlaybackLookLock(private val plugin: Nonscenes) : PacketListenerAbstract(PacketListenerPriority.HIGH) {

    override fun onPacketReceive(event: PacketReceiveEvent) {
        val player = event.getPlayer() as? Player ?: return
        if (!plugin.cutsceneManager.isWatchingCutscene(player)) return

        when (event.packetType) {
            PacketType.Play.Client.PLAYER_ROTATION,
            PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION -> {
                event.isCancelled = true
            }
            PacketType.Play.Client.CHAT_COMMAND,
            PacketType.Play.Client.CHAT_COMMAND_UNSIGNED -> {
                // unreadable packets are left to PlayerCommandPreprocessEvent
                val command = readChatCommand(event) ?: return
                if (PlaybackCommandPolicy.isAllowedDuringPlayback(command)) return
                event.isCancelled = true
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    if (player.isOnline) {
                        player.sendMessage(plugin.configManager.getMessage("command-disabled-during-cutscene"))
                    }
                })
            }
            else -> Unit
        }
    }

    private fun readChatCommand(event: PacketReceiveEvent): String? {
        return try {
            if (event.packetType == PacketType.Play.Client.CHAT_COMMAND_UNSIGNED) {
                WrapperPlayClientChatCommandUnsigned(event).command
            } else {
                WrapperPlayClientChatCommand(event).command
            }
        } catch (_: Exception) {
            null
        }
    }
}
