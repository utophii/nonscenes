package com.nonxedy.listener

import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.nonxedy.Nonscenes
import org.bukkit.entity.Player

class PlaybackLookLock(private val plugin: Nonscenes) : PacketListenerAbstract(PacketListenerPriority.HIGH) {

    override fun onPacketReceive(event: PacketReceiveEvent) {
        when (event.packetType) {
            PacketType.Play.Client.PLAYER_ROTATION,
            PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION -> {
                val player = event.getPlayer() as? Player ?: return
                if (plugin.cutsceneManager.isWatchingCutscene(player)) {
                    event.isCancelled = true
                }
            }
        }
    }
}
