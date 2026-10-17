package com.nonxedy.editor

import com.nonxedy.Nonscenes
import com.nonxedy.model.Cutscene
import com.nonxedy.util.CutsceneNames
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.scheduler.BukkitTask
import java.util.Locale
import java.util.UUID
import java.util.logging.Level
import kotlin.math.roundToLong

// in-game keyframe editor. Each editing player works on a private draft that is
// drawn only for them: the spline as dots spaced evenly in time (denser dots mean
// a slower camera), and a numbered label at every keyframe. Nothing is stored
// until /nonscene save.
// Keyframes are numbered from 1 for players. KeyframeTrack is 0-based
class KeyframeEditor(private val plugin: Nonscenes) : Listener {

    private class Session(
        val playerId: UUID,
        val name: String,
        val track: KeyframeTrack,
        var isNew: Boolean
    ) {
        var curve: List<Location> = emptyList()
        val labels = HashMap<Int, TextDisplay>()
        var visualsDirty = true
    }

    // main thread only
    private val sessions = HashMap<UUID, Session>()
    private var task: BukkitTask? = null

    private val messages get() = plugin.configManager
    private val cutscenes get() = plugin.cutsceneManager

    fun register() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 10L, DRAW_PERIOD_TICKS)
    }

    fun shutdown() {
        task?.cancel()
        task = null
        sessions.values.forEach { removeLabels(it) }
        sessions.clear()
    }

    fun isEditing(player: Player): Boolean = sessions.containsKey(player.uniqueId)

    fun isEditingCutscene(name: String): Boolean = sessions.values.any { it.name.equals(name, ignoreCase = true) }

    fun keyframeCount(player: Player): Int = sessions[player.uniqueId]?.track?.size ?: 0

    // session lifecycle
    fun open(player: Player, name: String, toleranceOverride: Double?) {
        sessions[player.uniqueId]?.let {
            player.sendMessage(msg("editor-already-open").replace("{name}", it.name))
            return
        }
        if (!CutsceneNames.isValid(name)) {
            player.sendMessage(msg("invalid-cutscene-name"))
            return
        }
        val takenBy = sessions.values.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (takenBy != null) {
            val other = Bukkit.getPlayer(takenBy.playerId)?.name ?: "someone"
            player.sendMessage(msg("editor-in-use").replace("{name}", name).replace("{player}", other))
            return
        }

        val settings = messages.getEditorSettings()
        val existing = cutscenes.getCutscene(name)
        val session = if (existing == null) {
            player.sendMessage(msg("editor-opened-new").replace("{name}", name))
            Session(player.uniqueId, name, KeyframeTrack(emptyList(), settings.defaultSegmentMs), isNew = true)
        } else {
            val raw = existing.isRawRecording
            val track = KeyframeTrack.from(
                existing,
                settings.defaultSegmentMs,
                toleranceOverride ?: settings.positionTolerance,
                settings.angleTolerance
            )
            if (raw) {
                player.sendMessage(
                    msg("editor-simplified")
                        .replace("{name}", existing.name)
                        .replace("{frames}", existing.frames.size.toString())
                        .replace("{keyframes}", track.size.toString())
                )
            } else {
                player.sendMessage(
                    msg("editor-opened")
                        .replace("{name}", existing.name)
                        .replace("{keyframes}", track.size.toString())
                )
            }
            Session(player.uniqueId, existing.name, track, isNew = false)
        }
        sessions[player.uniqueId] = session
        messages.getMessageList("editor-help").forEach { player.sendMessage(it) }
    }

    fun save(player: Player) {
        val session = session(player) ?: return
        if (session.track.size < 2) {
            player.sendMessage(msg("editor-need-two"))
            return
        }
        val base = cutscenes.getCutscene(session.name)
        if (session.isNew && base != null) {
            player.sendMessage(msg("cutscene-already-exists").replace("{name}", session.name))
            return
        }
        val cutscene = session.track.toCutscene(base?.name ?: session.name, base)
        try {
            cutscenes.saveEditedCutscene(cutscene)
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Failed to save edited cutscene: ${cutscene.name}", e)
            player.sendMessage(msg("error-occurred"))
            return
        }
        close(session)
        player.sendMessage(
            msg("editor-saved")
                .replace("{name}", cutscene.name)
                .replace("{keyframes}", cutscene.frames.size.toString())
                .replace("{seconds}", seconds(cutscene.totalDurationMs))
        )
        val late = cutscene.events.count { it.timeMs > cutscene.totalDurationMs }
        if (late > 0) {
            player.sendMessage(
                msg("editor-events-after-end")
                    .replace("{count}", late.toString())
                    .replace("{seconds}", seconds(cutscene.totalDurationMs))
            )
        }
    }

    fun discard(player: Player) {
        val session = session(player) ?: return
        close(session)
        player.sendMessage(msg("editor-discarded").replace("{name}", session.name))
    }

    fun preview(player: Player) {
        val session = session(player) ?: return
        if (session.track.size < 2) {
            player.sendMessage(msg("editor-need-two"))
            return
        }
        // labels would show up in front of the camera
        removeLabels(session)
        val base = cutscenes.getCutscene(session.name)
        cutscenes.previewCutscene(player, session.track.toCutscene(session.name, base))
    }

    // Keyframe operations, number is 1-based
    fun add(player: Player, number: Int?) {
        val session = session(player) ?: return
        val track = session.track
        val index = (number ?: (track.size + 1)) - 1
        if (index !in 0..track.size) {
            player.sendMessage(msg("editor-invalid-index").replace("{max}", (track.size + 1).toString()))
            return
        }
        track.insert(index, player.location)
        edited(session)
        player.sendMessage(
            msg("editor-key-added")
                .replace("{index}", (index + 1).toString())
                .replace("{keyframes}", track.size.toString())
        )
    }

    fun remove(player: Player, number: Int) {
        val session = session(player) ?: return
        val index = keyIndex(player, session, number) ?: return
        session.track.remove(index)
        edited(session)
        player.sendMessage(
            msg("editor-key-removed")
                .replace("{index}", number.toString())
                .replace("{keyframes}", session.track.size.toString())
        )
    }

    fun move(player: Player, number: Int) {
        val session = session(player) ?: return
        val index = keyIndex(player, session, number) ?: return
        session.track.move(index, player.location)
        edited(session)
        player.sendMessage(msg("editor-key-moved").replace("{index}", number.toString()))
    }

    fun speed(player: Player, number: Int, seconds: Double) {
        val session = session(player) ?: return
        val track = session.track
        if (number < 1 || number > track.size - 1) {
            player.sendMessage(msg("editor-invalid-segment").replace("{max}", (track.size - 1).coerceAtLeast(0).toString()))
            return
        }
        val ms = (seconds * 1000).roundToLong()
        if (seconds.isNaN() || ms < Cutscene.MIN_SEGMENT_MS || ms > KeyframeTrack.MAX_SEGMENT_MS) {
            player.sendMessage(msg("editor-invalid-duration"))
            return
        }
        track.setSegment(number - 1, ms)
        edited(session)
        player.sendMessage(
            msg("editor-segment-set")
                .replace("{from}", number.toString())
                .replace("{to}", (number + 1).toString())
                .replace("{seconds}", seconds(ms))
                .replace("{total}", seconds(track.totalMs))
        )
    }

    fun teleport(player: Player, number: Int) {
        val session = session(player) ?: return
        val index = keyIndex(player, session, number) ?: return
        val target = session.track[index].resolveLocation()
        if (target == null) {
            player.sendMessage(msg("editor-world-missing").replace("{world}", session.track[index].worldName))
            return
        }
        player.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN)
        if (player.allowFlight) player.isFlying = true
        player.sendMessage(msg("editor-key-teleported").replace("{index}", number.toString()))
    }

    fun list(player: Player) {
        val session = session(player) ?: return
        val track = session.track
        player.sendMessage(
            msg("editor-list-header")
                .replace("{name}", session.name)
                .replace("{keyframes}", track.size.toString())
                .replace("{seconds}", seconds(track.totalMs))
        )
        for (i in 0 until track.size) {
            val frame = track[i]
            val loc = frame.location
            val segment = if (i < track.size - 1) seconds(track.segmentMs(i)) + "s" else "-"
            player.sendMessage(
                msg("editor-list-item")
                    .replace("{index}", (i + 1).toString())
                    .replace("{time}", seconds(track.timeAt(i)))
                    .replace("{world}", frame.worldName)
                    .replace("{x}", String.format(Locale.ROOT, "%.1f", loc.x))
                    .replace("{y}", String.format(Locale.ROOT, "%.1f", loc.y))
                    .replace("{z}", String.format(Locale.ROOT, "%.1f", loc.z))
                    .replace("{segment}", segment)
            )
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        sessions[event.player.uniqueId]?.let { close(it) }
    }

    // internals
    private fun session(player: Player): Session? {
        val session = sessions[player.uniqueId]
        if (session == null) player.sendMessage(msg("editor-not-open"))
        return session
    }

    private fun keyIndex(player: Player, session: Session, number: Int): Int? {
        if (number < 1 || number > session.track.size) {
            player.sendMessage(msg("editor-invalid-index").replace("{max}", session.track.size.toString()))
            return null
        }
        return number - 1
    }

    private fun edited(session: Session) {
        session.visualsDirty = true
    }

    private fun close(session: Session) {
        removeLabels(session)
        sessions.remove(session.playerId)
    }

    private fun tick() {
        if (sessions.isEmpty()) return
        val eyeHeight = messages.getPlaybackSettings().eyeHeight
        for (session in sessions.values.toList()) {
            val player = Bukkit.getPlayer(session.playerId)
            if (player == null || !player.isOnline) {
                close(session)
                continue
            }
            if (cutscenes.isWatchingCutscene(player)) {
                removeLabels(session)
                continue
            }
            if (session.visualsDirty) {
                session.visualsDirty = false
                removeLabels(session)
                session.curve = if (session.track.size < 2) emptyList()
                else cutscenes.sampleCurve(session.track.toCutscene(session.name, null), CURVE_STEP_MS)
            }
            syncLabels(session, player, eyeHeight)
            draw(session, player, eyeHeight)
        }
    }

    private fun draw(session: Session, player: Player, eyeHeight: Double) {
        val here = player.location
        for (point in session.curve) {
            if (point.world != here.world || point.distanceSquared(here) > DRAW_RANGE_SQ) continue
            player.spawnParticle(Particle.END_ROD, point.x, point.y + eyeHeight, point.z, 1, 0.0, 0.0, 0.0, 0.0)
        }
        val track = session.track
        for (i in 0 until track.size) {
            val frame = track[i]
            val loc = frame.location
            if (frame.worldName != here.world.name) continue
            val eye = Location(here.world, loc.x, loc.y + eyeHeight, loc.z, loc.yaw, loc.pitch)
            if (eye.distanceSquared(here) > DRAW_RANGE_SQ) continue
            player.spawnParticle(Particle.FLAME, eye.x, eye.y, eye.z, 2, 0.05, 0.05, 0.05, 0.0)
            // where the camera looks from this keyframe
            val dir = eye.direction
            var d = 0.3
            while (d <= VIEW_RAY_LENGTH) {
                player.spawnParticle(
                    Particle.ELECTRIC_SPARK,
                    eye.x + dir.x * d, eye.y + dir.y * d, eye.z + dir.z * d,
                    1, 0.0, 0.0, 0.0, 0.0
                )
                d += 0.3
            }
        }
    }

    private fun syncLabels(session: Session, player: Player, eyeHeight: Double) {
        val track = session.track
        for (i in 0 until track.size) {
            val existing = session.labels[i]
            if (existing != null && existing.isValid) continue
            session.labels.remove(i)
            val frame = track[i]
            val world = Bukkit.getWorld(frame.worldName) ?: continue
            val loc = frame.location
            if (!world.isChunkLoaded(loc.blockX shr 4, loc.blockZ shr 4)) continue
            val at = Location(world, loc.x, loc.y + eyeHeight + LABEL_OFFSET, loc.z)
            val label = try {
                world.spawn(at, TextDisplay::class.java) { display ->
                    display.isPersistent = false
                    display.isVisibleByDefault = false
                    display.billboard = Display.Billboard.CENTER
                    display.isSeeThrough = true
                    display.text(labelText(track, i))
                }
            } catch (e: Exception) {
                plugin.logger.log(Level.FINE, "Could not spawn keyframe label", e)
                continue
            }
            player.showEntity(plugin, label)
            session.labels[i] = label
        }
    }

    private fun labelText(track: KeyframeTrack, i: Int): Component {
        var text = Component.text("#${i + 1}", NamedTextColor.GOLD)
            .append(Component.text("  ${seconds(track.timeAt(i))}s", NamedTextColor.GRAY))
        if (i < track.size - 1) {
            text = text.append(Component.newline())
                .append(Component.text("→ #${i + 2}: ${seconds(track.segmentMs(i))}s", NamedTextColor.AQUA))
        }
        return text
    }

    private fun removeLabels(session: Session) {
        session.labels.values.forEach { if (it.isValid) it.remove() }
        session.labels.clear()
    }

    private fun msg(key: String): String = messages.getMessage(key)

    private fun seconds(ms: Long): String =
        String.format(Locale.ROOT, "%.2f", ms / 1000.0).trimEnd('0').trimEnd('.')

    private companion object {
        const val DRAW_PERIOD_TICKS = 10L
        const val CURVE_STEP_MS = 100L
        const val DRAW_RANGE_SQ = 64.0 * 64.0
        const val VIEW_RAY_LENGTH = 1.5
        const val LABEL_OFFSET = 0.6
    }
}
