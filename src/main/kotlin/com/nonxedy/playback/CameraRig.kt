package com.nonxedy.playback

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCamera
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChangeGameState
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.Optional
import java.util.UUID

// Client-side only camera. The viewer looks through an invisible marker armor
// stand that exists only in their client, and is switched to spectator mode
// locally so the hotbar, hand and input are gone. The server never sees either
// change, so nothing has to be undone if the player disconnects.
class CameraRig(private val player: Player, private val eyeHeight: Double) {

    private val entityId = Bukkit.getUnsafe().nextEntityId()
    private val uuid = UUID.randomUUID()

    @Volatile
    private var attached = false

    // Spawns the camera entity and moves the view into it. Call again after the
    // player changes worlds, since the client drops all entities on respawn.
    fun attach(at: Location) {
        send(
            WrapperPlayServerSpawnEntity(
                entityId,
                Optional.of(uuid),
                EntityTypes.ARMOR_STAND,
                eyePosition(at),
                at.pitch,
                at.yaw,
                at.yaw,
                0,
                Optional.empty()
            )
        )
        send(
            WrapperPlayServerEntityMetadata(
                entityId,
                listOf(
                    EntityData(0, EntityDataTypes.BYTE, FLAG_INVISIBLE),
                    EntityData(5, EntityDataTypes.BOOLEAN, true),
                    EntityData(15, EntityDataTypes.BYTE, ARMOR_STAND_MARKER)
                )
            )
        )
        send(WrapperPlayServerEntityHeadLook(entityId, at.yaw))
        send(WrapperPlayServerChangeGameState(WrapperPlayServerChangeGameState.Reason.CHANGE_GAME_MODE, SPECTATOR))
        send(WrapperPlayServerCamera(entityId))
        attached = true
    }

    // Safe to call from the playback thread.
    fun move(to: Location) {
        if (!attached) return
        send(WrapperPlayServerEntityTeleport(entityId, eyePosition(to), to.yaw, to.pitch, false))
        send(WrapperPlayServerEntityHeadLook(entityId, to.yaw))
    }

    // Gives the view back to the player and restores their real game mode on the client.
    fun detach() {
        if (!attached) return
        attached = false
        if (!player.isOnline) return
        send(WrapperPlayServerCamera(player.entityId))
        send(WrapperPlayServerDestroyEntities(entityId))
        @Suppress("DEPRECATION")
        val mode = player.gameMode.value.toFloat()
        send(WrapperPlayServerChangeGameState(WrapperPlayServerChangeGameState.Reason.CHANGE_GAME_MODE, mode))
    }

    // A marker armor stand has zero eye height, so the camera sits exactly here.
    private fun eyePosition(loc: Location): Vector3d = Vector3d(loc.x, loc.y + eyeHeight, loc.z)

    private fun send(packet: PacketWrapper<*>) {
        if (!PacketEvents.getAPI().isInitialized) return
        try {
            PacketEvents.getAPI().playerManager.sendPacket(player, packet)
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val SPECTATOR = 3f
        const val FLAG_INVISIBLE: Byte = 0x20
        const val ARMOR_STAND_MARKER: Byte = 0x10
    }
}
