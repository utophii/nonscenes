package com.nonxedy.playback

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerRotation
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// Packet-based cutscene playback
class AsyncPacketPlaybackController(
    private val plugin: JavaPlugin,
    private val updateRate: Int,
    private val rideHeightOffset: Double,
    private val onComplete: () -> Unit,
    private val onCancel: () -> Unit
) : CutscenePlaybackController {

    private val active = AtomicBoolean(false)
    private val cleanedUp = AtomicBoolean(false)
    private var executor: ScheduledExecutorService? = null
    private var carrier: ArmorStand? = null
    private var originalLocation: Location? = null

    private var playerRef: Player? = null
    private var wasFlying = false
    private var wasAllowedFlight = false
    private val lastFollowChunkX = AtomicInteger(Int.MIN_VALUE)
    private val lastFollowChunkZ = AtomicInteger(Int.MIN_VALUE)

    override fun start(player: Player, path: List<Location>, totalDurationMs: Long) {
        if (path.isEmpty()) {
            onComplete()
            return
        }

        active.set(true)
        cleanedUp.set(false)
        playerRef = player
        originalLocation = player.location.clone()

        val pathArray = path.toTypedArray()

        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (!active.get() || !player.isOnline) {
                cleanup(player)
                return@Runnable
            }

            wasFlying = player.isFlying
            wasAllowedFlight = player.allowFlight
            player.hidePlayer(player)
            player.setAllowFlight(true)
            player.setFlying(true)

            val stand = spawnCarrier(player, pathArray[0])
            carrier = stand
            stand.addPassenger(player)

            if (!active.get() || !player.isOnline) {
                cleanup(player)
                return@Runnable
            }

            beginPacketLoop(player, pathArray, totalDurationMs)
        })
    }

    private fun beginPacketLoop(player: Player, pathArray: Array<Location>, totalDurationMs: Long) {
        if (!active.get()) return

        val lastIndex = pathArray.lastIndex
        val intervalNs = 1_000_000_000L / updateRate
        val startTime = System.currentTimeMillis()

        executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "nonscenes-async-playback-${player.uniqueId.toString().take(8)}")
        }.apply {
            scheduleWithFixedDelay({
                if (!active.get() || !player.isOnline) {
                    active.set(false)
                    shutdown()
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        cleanup(player)
                        onCancel()
                    })
                    return@scheduleWithFixedDelay
                }

                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= totalDurationMs) {
                    active.set(false)
                    shutdown()
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        cleanup(player)
                        onComplete()
                    })
                    return@scheduleWithFixedDelay
                }

                val progress = elapsed.toDouble() / totalDurationMs.toDouble()
                val loc = samplePath(pathArray, lastIndex, progress)
                sendCameraPackets(player, loc)
                followChunkIfNeeded(player, loc)

                val index = (progress * lastIndex).toInt().coerceIn(0, lastIndex)
                if (index % (updateRate / 4).coerceAtLeast(1) == 0) {
                    val text = " ${index + 1} / ${pathArray.size}"
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        if (player.isOnline) {
                            player.sendActionBar(MiniMessage.miniMessage().deserialize(text))
                        }
                    })
                }
            }, 0L, intervalNs, TimeUnit.NANOSECONDS)
        }
    }

    override fun stop() {
        active.set(false)
        shutdown()
        val p = playerRef
        if (p != null) {
            Bukkit.getScheduler().runTask(plugin, Runnable { cleanup(p) })
        }
    }

    override fun isActive(): Boolean = active.get()

    
    // Interpolates between the two neighbouring path points using the fractional
    // progress `t` (in 0..1). `lastIndex` is the index of the final path point
    private fun samplePath(path: Array<Location>, lastIndex: Int, t: Double): Location {
        val scaled = t * lastIndex
        val idx = scaled.toInt().coerceIn(0, lastIndex)
        val frac = (scaled - idx).coerceIn(0.0, 1.0)

        val from = path[idx]
        val to = path[(idx + 1).coerceAtMost(lastIndex)]

        val x = from.x + (to.x - from.x) * frac
        val y = from.y + (to.y - from.y) * frac
        val z = from.z + (to.z - from.z) * frac
        val yaw = lerpAngle(from.yaw, to.yaw, frac)
        val pitch = (from.pitch + (to.pitch - from.pitch) * frac).toFloat()

        return Location(from.world, x, y, z, yaw, pitch)
    }

    private fun lerpAngle(from: Float, to: Float, t: Double): Float {
        var delta = ((to - from) % 360f + 360f) % 360f
        if (delta > 180f) delta -= 360f
        return (from + delta * t).toFloat()
    }

    private fun sendCameraPackets(player: Player, loc: Location) {
        if (!PacketEvents.getAPI().isInitialized) return
        try {
            val stand = carrier
            if (stand != null && !stand.isDead) {
                PacketEvents.getAPI().playerManager.sendPacket(
                    player,
                    WrapperPlayServerEntityTeleport(
                        stand.entityId,
                        Vector3d(loc.x, loc.y + rideHeightOffset, loc.z),
                        loc.yaw,
                        loc.pitch,
                        true
                    )
                )
            }
            PacketEvents.getAPI().playerManager.sendPacket(
                player,
                WrapperPlayServerPlayerRotation(loc.yaw, loc.pitch)
            )
        } catch (_: Exception) {
        }
    }

    private fun followChunkIfNeeded(player: Player, loc: Location) {
        val cx = loc.blockX shr 4
        val cz = loc.blockZ shr 4
        if (cx == lastFollowChunkX.get() && cz == lastFollowChunkZ.get()) return
        lastFollowChunkX.set(cx)
        lastFollowChunkZ.set(cz)
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (!active.get() || !player.isOnline) return@Runnable
            keepCameraChunkLoaded(player, loc)
        })
    }

    private fun keepCameraChunkLoaded(player: Player, loc: Location) {
        val world = loc.world ?: return
        val cx = loc.blockX shr 4
        val cz = loc.blockZ shr 4
        for (dx in -1..1) {
            for (dz in -1..1) {
                val x = cx + dx
                val z = cz + dz
                if (!world.isChunkLoaded(x, z)) {
                    try {
                        world.getChunkAtAsync(x, z)
                    } catch (_: NoSuchMethodError) {
                        world.loadChunk(x, z, true)
                    }
                }
            }
        }
        if (!world.isChunkLoaded(cx, cz)) {
            world.loadChunk(cx, cz, true)
        }

        val carrierLoc = loc.clone().add(0.0, rideHeightOffset, 0.0)
        val stand = carrier
        if (stand == null || stand.isDead) {
            val spawned = spawnCarrier(player, loc)
            carrier = spawned
            spawned.addPassenger(player)
        } else {
            stand.teleport(carrierLoc)
        }
    }

    private fun spawnCarrier(player: Player, at: Location): ArmorStand {
        return player.world.spawn(at.clone().add(0.0, rideHeightOffset, 0.0), ArmorStand::class.java) { stand ->
            stand.isVisible = false
            stand.setGravity(false)
            stand.isInvulnerable = true
            stand.isSilent = true
            stand.setBasePlate(false)
            stand.isSmall = true
            stand.isMarker = true
            stand.customName = null
            stand.setCollidable(false)
        }
    }

    private fun cleanup(player: Player) {
        shutdown()
        if (!cleanedUp.compareAndSet(false, true)) return
        playerRef = null

        val stand = carrier
        carrier = null
        if (stand != null && !stand.isDead) {
            stand.removePassenger(player)
            stand.remove()
        }

        if (!player.isOnline) {
            originalLocation = null
            return
        }

        player.showPlayer(player)
        player.setFlying(wasFlying)
        player.setAllowFlight(wasAllowedFlight)

        // Bring the player back to where they started the cutscene.
        originalLocation?.let { loc ->
            player.teleport(loc)
        }
        originalLocation = null
    }

    private fun shutdown() {
        try {
            executor?.shutdownNow()
        } catch (_: Exception) {
        }
        executor = null
    }
}
