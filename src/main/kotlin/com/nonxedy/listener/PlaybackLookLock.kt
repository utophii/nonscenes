package com.nonxedy.listener

import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatCommand
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
            else -> {
                if (isBrigadierCommandPacket(event)) {
                    val command = readChatCommand(event)
                    if (!PlaybackCommandPolicy.isAllowedDuringPlayback(command)) {
                        event.isCancelled = true
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            if (player.isOnline) {
                                player.sendMessage(plugin.configManager.getMessage("command-disabled-during-cutscene"))
                            }
                        })
                    }
                }
            }
        }
    }

    private fun isBrigadierCommandPacket(event: PacketReceiveEvent): Boolean {
        val name = event.packetType.name
        return name.contains("CHAT_COMMAND")
    }

    private fun readChatCommand(event: PacketReceiveEvent): String {
        return try {
            WrapperPlayClientChatCommand(event).command.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }
}
