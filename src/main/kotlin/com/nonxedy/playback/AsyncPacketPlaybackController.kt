package com.nonxedy.playback

import com.nonxedy.model.playback.FadeSettings
import com.nonxedy.model.timeline.TimelineEvent
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// Packet-based cutscene playback
class AsyncPacketPlaybackController(
    private val plugin: JavaPlugin,
    private val executor: ScheduledExecutorService,
    private val recovery: PlaybackRecovery,
    private val updateRate: Int,
    private val eyeHeight: Double,
    private val fade: FadeSettings,
    private val onComplete: () -> Unit,
    private val onCancel: () -> Unit,
    private val timelineEvents: List<TimelineEvent> = emptyList(),
    private val cutsceneName: String = ""
) : CutscenePlaybackController {

    private val active = AtomicBoolean(false)
    private val cleanedUp = AtomicBoolean(false)
    private val fadingOut = AtomicBoolean(false)
    private val lastActionBarNs = AtomicLong(0L)
    @Volatile private var loop: ScheduledFuture<*>? = null
    @Volatile private var rig: CameraRig? = null
    @Volatile private var playerRef: Player? = null

    // main thread only
    private var originalLocation: Location? = null
    private var entered = false
    private var wasFlying = false
    private var wasAllowedFlight = false

    @Volatile private var anchorWorld: World? = null
    @Volatile private var anchorChunkX = Int.MIN_VALUE
    @Volatile private var anchorChunkZ = Int.MIN_VALUE

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

        // darken first, then cut to the camera once the screen is black
        ScreenFade.cover(player, fade)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!active.get() || !player.isOnline) {
                cleanup(player)
                return@Runnable
            }

            enterCamera(player, pathArray[0])
            beginPacketLoop(player, pathArray, totalDurationMs)
        }, if (fade.enabled) fade.outTicks.toLong() else 0L)
    }

    private fun enterCamera(player: Player, first: Location) {
        entered = true
        wasFlying = player.isFlying
        wasAllowedFlight = player.allowFlight
        originalLocation?.let { recovery.save(player, it, wasFlying, wasAllowedFlight) }
        player.leaveVehicle()
        player.setAllowFlight(true)
        player.setFlying(true)
        for (other in Bukkit.getOnlinePlayers()) {
            if (other != player) other.hidePlayer(plugin, player)
        }

        // the manager preloaded the path's chunks
        markAnchor(first)
        player.teleport(first, PlayerTeleportEvent.TeleportCause.PLUGIN)
        rig = CameraRig(player, eyeHeight, plugin.logger).also { it.attach(first) }
    }

    private fun beginPacketLoop(player: Player, pathArray: Array<Location>, totalDurationMs: Long) {
        if (!active.get()) return

        val lastIndex = pathArray.lastIndex
        val intervalNs = 1_000_000_000L / updateRate
        val startNs = System.nanoTime()
        val timeline = TimelineClock(plugin, player, cutsceneName, timelineEvents)
        val fadeOutAt = totalDurationMs - fade.outMs

        loop = executor.scheduleWithFixedDelay({
            if (!active.get() || !player.isOnline) {
                active.set(false)
                shutdown()
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    cleanup(player)
                    onCancel()
                })
                return@scheduleWithFixedDelay
            }

            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
            timeline.pulse(elapsed)
            if (fade.enabled && elapsed >= fadeOutAt && fadingOut.compareAndSet(false, true)) {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    if (active.get() && player.isOnline) ScreenFade.cover(player, fade)
                })
            }
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
            rig?.move(loc)
            followAnchorIfNeeded(player, loc)

            val now = System.nanoTime()
            val last = lastActionBarNs.get()
            if (now - last >= ACTION_BAR_INTERVAL_NS && lastActionBarNs.compareAndSet(last, now)) {
                val index = (progress * lastIndex).toInt().coerceIn(0, lastIndex)
                val text = " ${index + 1} / ${pathArray.size}"
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    if (player.isOnline) {
                        player.sendActionBar(MINI_MESSAGE.deserialize(text))
                    }
                })
            }
        }, 0L, intervalNs, TimeUnit.NANOSECONDS)
    }

    override fun stop() {
        active.set(false)
        shutdown()
        val p = playerRef ?: return
        // On plugin disable the scheduler no longer runs tasks, so clean up inline.
        if (Bukkit.isPrimaryThread()) {
            cleanup(p)
        } else {
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
        if (from.world != to.world) {
            return if (frac < 1.0) from.clone() else to.clone()
        }

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

    private fun followAnchorIfNeeded(player: Player, loc: Location) {
        val cx = loc.blockX shr 4
        val cz = loc.blockZ shr 4
        if (loc.world == anchorWorld && cx == anchorChunkX && cz == anchorChunkZ) return
        anchorWorld = loc.world
        anchorChunkX = cx
        anchorChunkZ = cz
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (!active.get() || !player.isOnline) return@Runnable
            val changedWorld = player.world != loc.world
            player.teleportAsync(loc, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept { moved ->
                // a world change respawns the client, which drops the camera entity
                // and resets the local game mode
                if (moved && changedWorld && active.get() && player.isOnline) rig?.attach(loc)
            }
        })
    }

    private fun markAnchor(loc: Location) {
        anchorWorld = loc.world
        anchorChunkX = loc.blockX shr 4
        anchorChunkZ = loc.blockZ shr 4
    }

    private fun cleanup(player: Player) {
        shutdown()
        if (!cleanedUp.compareAndSet(false, true)) return
        playerRef = null

        val camera = rig
        rig = null

        if (!player.isOnline || !entered) {
            originalLocation = null
            return
        }

        camera?.detach()
        for (other in Bukkit.getOnlinePlayers()) {
            if (other != player) other.showPlayer(plugin, player)
        }
        player.setFlying(wasFlying)
        player.setAllowFlight(wasAllowedFlight)

        // Bring the player back to where they started the cutscene.
        originalLocation?.let { loc ->
            player.teleport(loc, PlayerTeleportEvent.TeleportCause.PLUGIN)
        }
        originalLocation = null
        recovery.clear(player)
        player.updateInventory()
    }

    private fun shutdown() {
        loop?.cancel(false)
        loop = null
    }

    private companion object {
        val MINI_MESSAGE: MiniMessage = MiniMessage.miniMessage()
        val ACTION_BAR_INTERVAL_NS: Long = TimeUnit.MILLISECONDS.toNanos(250)
    }
}
