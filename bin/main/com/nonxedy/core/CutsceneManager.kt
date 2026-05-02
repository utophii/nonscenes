package com.nonxedy.core

import com.nonxedy.Nonscenes
import com.nonxedy.database.service.CutsceneDatabaseService
import com.nonxedy.database.service.impl.SQLiteCutsceneDatabaseService
import com.nonxedy.model.Cutscene
import com.nonxedy.model.CutsceneFrame
import com.nonxedy.util.ColorUtil
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import java.io.File
import java.io.IOException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import kotlin.math.max
import kotlin.math.min

class CutsceneManager(private val plugin: Nonscenes) : CutsceneManagerInterface {
    private val databaseService: CutsceneDatabaseService
    private val cutscenes = mutableMapOf<String, Cutscene>()
    private val playerSessions = ConcurrentHashMap<UUID, PlayerSession>()
    private val sessionTasks = ConcurrentHashMap<UUID, BukkitTask>()
    private val savedInventories = ConcurrentHashMap<UUID, Array<ItemStack?>>()
    private val savedGameModes = ConcurrentHashMap<UUID, GameMode>()
    private val savedLocations = ConcurrentHashMap<UUID, Location>()
    private val cutsceneFolder = File(plugin.dataFolder, "cutscenes")

    init {
        // Initialize SQLite database
        val databaseFile = File(plugin.dataFolder, "cutscenes.db")
        databaseService = SQLiteCutsceneDatabaseService(databaseFile)

        try {
            databaseService.initialize()
            // Load cutscenes from database
            loadCutscenesFromDatabase()
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Failed to initialize database, falling back to file storage", e)
            // Fallback to file storage if database fails
            loadCutscenesFromFiles()
        }
    }

    // Load cutscenes from database
    private fun loadCutscenesFromDatabase() {
        try {
            val loadedCutscenes = databaseService.loadAllCutscenes()
            for (cutscene in loadedCutscenes) {
                cutscenes[cutscene.name.lowercase()] = cutscene
            }
            plugin.logger.info("Loaded ${loadedCutscenes.size} cutscenes from database")
        } catch (e: Exception) {
            plugin.logger.log(java.util.logging.Level.SEVERE, "Failed to load cutscenes from database", e)
        }
    }

    private fun loadCutscenesFromFiles() {
        val cutsceneFolder = java.io.File(plugin.dataFolder, "cutscenes")
        val files = cutsceneFolder.listFiles { _, name -> name.endsWith(".yml") } ?: return

        var fileCount = 0
        for (file in files) {
            try {
                val config = YamlConfiguration.loadConfiguration(file)
                val name = file.name.replace(".yml", "")

                // Skip if already loaded from database
                if (cutscenes.containsKey(name.lowercase())) {
                    continue
                }

                val frames = mutableListOf<CutsceneFrame>()
                val framesSection: ConfigurationSection? = config.getConfigurationSection("frames")

                if (framesSection != null) {
                    for (key in framesSection.getKeys(false)) {
                        val frameSection = framesSection.getConfigurationSection(key)
                        if (frameSection != null) {
                            val worldName = frameSection.getString("world")
                            val x = frameSection.getDouble("x")
                            val y = frameSection.getDouble("y")
                            val z = frameSection.getDouble("z")
                            val yaw = frameSection.getDouble("yaw").toFloat()
                            val pitch = frameSection.getDouble("pitch").toFloat()

                            if (worldName != null && Bukkit.getWorld(worldName) != null) {
                                val location = Location(Bukkit.getWorld(worldName), x, y, z, yaw, pitch)
                                frames.add(CutsceneFrame(location))
                            }
                        }
                    }
                }

                if (frames.isNotEmpty()) {
                    val cutscene = Cutscene(name, frames)
                    cutscenes[name.lowercase()] = cutscene
                    fileCount++

                    // Try to save to database for migration
                    try {
                        databaseService.saveCutscene(cutscene)
                        plugin.logger.info("Migrated cutscene from file to database: $name")
                    } catch (dbException: Exception) {
                        plugin.logger.log(java.util.logging.Level.WARNING, "Failed to migrate cutscene to database: $name", dbException)
                    }
                }
            } catch (e: Exception) {
                plugin.logger.warning("Failed to load cutscene from file: ${file.name}")
            }
        }

        if (fileCount > 0) {
            plugin.logger.info("Loaded $fileCount cutscenes from files")
        }
    }

    private fun saveCutscene(cutscene: Cutscene) {
        val file = File(cutsceneFolder, "${cutscene.name}.yml")
        val config = YamlConfiguration()

        config.set("name", cutscene.name)

        val frames = cutscene.frames
        for (i in frames.indices) {
            val frame = frames[i]
            val location = frame.location

            config.set("frames.$i.world", location.world?.name ?: "world")
            config.set("frames.$i.x", location.x)
            config.set("frames.$i.y", location.y)
            config.set("frames.$i.z", location.z)
            config.set("frames.$i.yaw", location.yaw)
            config.set("frames.$i.pitch", location.pitch)
        }

        try {
            config.save(file)
        } catch (e: IOException) {
            plugin.logger.warning("Failed to save cutscene: ${cutscene.name}")
        }
    }

    override fun startRecording(player: Player, name: String, frames: Int) {
        val playerId = player.uniqueId

        if (playerSessions.containsKey(playerId)) {
            val message = plugin.configManager.getMessage("already-recording")
            player.sendMessage(message)
            return
        }

        if (cutscenes.containsKey(name.lowercase())) {
            val message = plugin.configManager.getMessage("cutscene-already-exists")?.replace("{name}", name) ?: "§cA cutscene with that name already exists!"
            player.sendMessage(message)
            return
        }

        val countdownSeconds = plugin.configManager.config?.getInt("settings.countdown-seconds", 3) ?: 3
        val countdownMessage = plugin.configManager.getMessage("recording-countdown")
            ?.replace("{seconds}", countdownSeconds.toString())
            ?: "§aRecording will start in $countdownSeconds seconds..."
        player.sendMessage(countdownMessage)

        object : BukkitRunnable() {
            var seconds = countdownSeconds

            override fun run() {
                if (seconds > 0) {
                    val countdownTickMessage = plugin.configManager.getMessage("countdown")?.replace("{seconds}", seconds.toString()) ?: "§e$seconds..."
                    player.sendMessage(countdownTickMessage)
                    seconds--
                } else {
                    cancel()
                    startRecordingProcess(player, name, frames)
                }
            }
        }.runTaskTimer(plugin, 0L, 20L)
    }

    private fun startRecordingProcess(player: Player, name: String, totalFrames: Int) {
        val playerId = player.uniqueId
        val frames = mutableListOf<CutsceneFrame>()

        // Create recording session
        val session = PlayerSession.Recording(playerId, name, totalFrames)
        playerSessions[playerId] = session

        val message = plugin.configManager.getMessage("recording-started")?.replace("{name}", name) ?: "§aStarted recording cutscene '$name'!"
        player.sendMessage(message)

        val framesPerSecond = plugin.configManager.config?.getInt("settings.frames-per-second", 30) ?: 30
        val delay = max(1L, 20L / framesPerSecond)

        val task = object : BukkitRunnable() {
            var frameCount = 0

            override fun run() {
                if (frameCount >= totalFrames) {
                    finishRecording(player, name, frames)
                    cancel()
                    return
                }

                frames.add(CutsceneFrame(player.location.clone()))
                frameCount++

                // Update session with current frame count
                playerSessions[playerId] = session.copy(frameCount = frameCount)

                if (frameCount % framesPerSecond == 0 || frameCount == totalFrames) {
                    val progressMessage = plugin.configManager.getMessage("recording-progress")
                        ?.replace("{current}", frameCount.toString())
                        ?.replace("{total}", totalFrames.toString()) ?: "§7Recorded $frameCount/$totalFrames frames"
                    player.sendMessage(progressMessage)
                }
            }
        }.runTaskTimer(plugin, 0L, delay)

        sessionTasks[playerId] = task
    }

    private fun finishRecording(player: Player, name: String, frames: List<CutsceneFrame>) {
        val playerId = player.uniqueId

        val cutscene = Cutscene(name, frames)
        cutscenes[name.lowercase()] = cutscene
        saveCutscene(cutscene)

        val message = plugin.configManager.getMessage("recording-finished")
            ?.replace("{name}", name)
            ?.replace("{frames}", frames.size.toString()) ?: "§aFinished recording cutscene '$name' with ${frames.size} frames!"
        player.sendMessage(message)

        // Clean up session
        playerSessions.remove(playerId)
        sessionTasks.remove(playerId)
    }

    override fun playCutscene(player: Player, name: String) {
        val playerId = player.uniqueId

        if (playerSessions.containsKey(playerId)) {
            player.sendMessage(plugin.configManager.getMessage("already-playing") ?: "")
            return
        }

        val cutscene = cutscenes[name.lowercase()]
        if (cutscene == null || cutscene.frames.isEmpty()) {
            player.sendMessage(plugin.configManager.getMessage("cutscene-not-found")
                ?.replace("{name}", name) ?: "§cNot found")
            return
        }

        val frames = cutscene.frames
        val session = PlayerSession.Playback(playerId, name, 0, frames.size)
        playerSessions[playerId] = session

        player.sendMessage(plugin.configManager.getMessage("cutscene-playing")
            ?.replace("{name}", name) ?: "§aPlaying...")

        savedGameModes[playerId] = player.gameMode
        savedLocations[playerId] = player.location.clone()
        player.gameMode = GameMode.SPECTATOR

        // Teleport immediately to the start and teleport until the chunks are fully loaded
        player.teleport(frames[0].location)

        // Asynchronous preloading of ALL chunks
        preloadChunksAsync(frames)

        val framesPerSecond = plugin.configManager.config
            ?.getInt("settings.frames-per-second", 30) ?: 30

        // The duration of one keyframe in ms
        val frameDurationMs = 1000L / framesPerSecond.coerceAtLeast(1)
        val totalDurationMs = frameDurationMs * (frames.size - 1)

        val startTime = System.currentTimeMillis()

        // ③ Один тик = 50 мс. Позиция рассчитывается по реальному времени.
        val task = object : BukkitRunnable() {
            override fun run() {
                // Игрок мог выйти или сессия отменена
                if (!player.isOnline() || !playerSessions.containsKey(playerId)) {
                    cancel(); return
                }

                val elapsed = System.currentTimeMillis() - startTime

                // The cutscene is complete
                if (elapsed >= totalDurationMs) {
                    player.teleport(frames.last().location)
                    finishPlayback(player, name, savedLocations[playerId] ?: frames[0].location)
                    cancel(); return
                }

                // Determine the current segment [frameIndex -> ​​frameIndex+1]
                val frameIndex = (elapsed / frameDurationMs).toInt().coerceIn(0, frames.size - 2)

                // Normalized t within the current segment [0..1]
                val rawT = ((elapsed - frameIndex * frameDurationMs) /
                             frameDurationMs.toFloat()).coerceIn(0f, 1f)

                // Apply smoothStep easing
                val easedT = smoothStep(rawT)

                // Position on the Catmull-Rom spline
                val loc = interpolateCatmull(frames, frameIndex, easedT)

                // The chunk isnt loaded - just wait for the next tick
                val cx = loc.blockX shr 4
                val cz = loc.blockZ shr 4
                if (!loc.world.isChunkLoaded(cx, cz)) return

                player.teleport(loc)

                // ⑩ Action bar прогресс
                val progressText = " ${frameIndex + 1} / ${frames.size}"
                player.sendActionBar(MiniMessage.miniMessage().deserialize(progressText))

                playerSessions[playerId] = session.copy(currentFrame = frameIndex + 1)
            }
        }.runTaskTimer(plugin, 0L, 1L) // Every tick (50 ms)

        sessionTasks[playerId] = task
    }

    private fun preloadChunksAsync(frames: List<CutsceneFrame>) {
        val world = frames.firstOrNull()?.location?.world ?: return

        // Collect unique chunks + neighbors in R=2
        val chunks = mutableSetOf<Pair<Int, Int>>()
        frames.forEach { frame ->
            val cx = frame.location.blockX shr 4
            val cz = frame.location.blockZ shr 4
            for (dx in -2..2) {
                for (dz in -2..2) {
                    chunks.add((cx + dx) to (cz + dz))
                }
            }
        }

        chunks.forEach { (cx, cz) ->
            if (!world.isChunkLoaded(cx, cz)) {
                try {
                    // async
                    world.getChunkAtAsync(cx, cz)
                } catch (_: NoSuchMethodError) {
                    // Fallback for Spigot
                    plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                        world.loadChunk(cx, cz, false)
                    })
                }
            }
        }

        plugin.logger.info("Preloading ${chunks.size} chunks for cutscene...")
    }

    // Creates curve through 4 points: p0..p3, t ∈ [0,1] - position between p1 and p2
    // α = 0.5 (centripetal) - prevents artifacts with uneven points
    private fun catmullRom(
        p0: Double, p1: Double,
        p2: Double, p3: Double,
        t: Float
    ): Double {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5 * (
            (2.0 * p1) +
            (-p0 + p2) * t +
            (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2 +
            (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3
        )
    }

    // Separate corner processing: normalizes the difference in the range [-180, 180]
    private fun catmullRomAngle(
        a0: Float, a1: Float,
        a2: Float, a3: Float,
        t: Float
    ): Float {
        fun norm(a: Float) = ((a % 360f) + 360f) % 360f
        fun diff(from: Float, to: Float): Float {
            var d = norm(to) - norm(from)
            if (d > 180f)  d -= 360f
            if (d < -180f) d += 360f
            return d
        }
       // We construct points relative to a1, maintaining continuity
        val b0 = a1 - diff(a0, a1)
        val b2 = a1 + diff(a1, a2)
        val b3 = b2 + diff(a2, a3)
        return catmullRom(b0.toDouble(), a1.toDouble(), b2.toDouble(), b3.toDouble(), t).toFloat()
    }

    // t²(3 - 2t): derivative = 0 at t=0 and t=1 -> no velocity jumps
    private fun smoothStep(t: Float): Float = t * t * (3f - 2f * t)

    private fun interpolateCatmull(
        frames: List<CutsceneFrame>,
        index: Int,
        t: Float
    ): Location {
        val n = frames.size
        // Take 4 adjacent keyframes and clamp them at the edges
        val i0 = (index - 1).coerceAtLeast(0)
        val i1 = index
        val i2 = (index + 1).coerceAtMost(n - 1)
        val i3 = (index + 2).coerceAtMost(n - 1)

        val p0 = frames[i0].location
        val p1 = frames[i1].location
        val p2 = frames[i2].location
        val p3 = frames[i3].location

        val world = p1.world
        val x = catmullRom(p0.x, p1.x, p2.x, p3.x, t)
        val y = catmullRom(p0.y, p1.y, p2.y, p3.y, t)
        val z = catmullRom(p0.z, p1.z, p2.z, p3.z, t)
        val yaw = catmullRomAngle(p0.yaw, p1.yaw, p2.yaw, p3.yaw, t)
        val pitch = catmullRomAngle(p0.pitch, p1.pitch, p2.pitch, p3.pitch, t)

        return Location(world, x, y, z, yaw, pitch)
    }

    private fun finishPlayback(player: Player, name: String, originalLocation: Location) {
        val playerId = player.uniqueId

        // Restore player's game mode
        val savedGameMode = savedGameModes.remove(playerId)
        if (savedGameMode != null) {
            player.gameMode = savedGameMode
        }

        // Ensure the original location chunk is loaded before teleporting back
        if (!originalLocation.world.isChunkLoaded(originalLocation.blockX shr 4, originalLocation.blockZ shr 4)) {
            originalLocation.world.loadChunk(originalLocation.blockX shr 4, originalLocation.blockZ shr 4, true)
        }

        player.teleport(originalLocation)
        savedLocations.remove(playerId)
        val message = plugin.configManager.getMessage("cutscene-playback-finished")?.replace("{name}", name) ?: "§aFinished playing cutscene '$name'!"
        player.sendMessage(message)

        // Clean up session
        playerSessions.remove(playerId)
        sessionTasks.remove(playerId)
    }

    override fun deleteCutscene(player: Player, name: String) {
        if (!cutscenes.containsKey(name.lowercase())) {
            val message = plugin.configManager.getMessage("cutscene-not-found")?.replace("{name}", name) ?: "§cCutscene '$name' not found!"
            player.sendMessage(message)
            return
        }

        // Delete file if it exists
        val file = File(cutsceneFolder, "$name.yml")
        if (file.exists()) {
            file.delete()
        }

        cutscenes.remove(name.lowercase())
        val message = plugin.configManager.getMessage("cutscene-deleted")?.replace("{name}", name) ?: "§aDeleted cutscene '$name'!"
        player.sendMessage(message)
    }

    override fun listAllCutscenes(player: Player) {
        if (cutscenes.isEmpty()) {
            val message = plugin.configManager.getMessage("no-cutscenes") ?: "§7No cutscenes found."
            player.sendMessage(message)
            return
        }

        val headerMessage = plugin.configManager.getMessage("cutscene-list-header") ?: "§6=== Available Cutscenes ==="
        player.sendMessage(headerMessage)

        for ((_, cutscene) in cutscenes) {
            val itemMessage = plugin.configManager.getMessage("cutscene-list-item")
                ?.replace("{name}", cutscene.name)
                ?.replace("{frames}", cutscene.frames.size.toString()) ?: "§7- §f${cutscene.name} §7(${cutscene.frames.size} frames)"
            player.sendMessage(itemMessage)
        }
    }

    override fun showCutscenePath(player: Player, name: String) {
        val playerId = player.uniqueId

        if (playerSessions.containsKey(playerId)) {
            val message = plugin.configManager.getMessage("path-already-showing") ?: "§cYou are already visualizing a path!"
            player.sendMessage(message)
            return
        }

        val cutscene = cutscenes[name.lowercase()]
        if (cutscene == null) {
            val message = plugin.configManager.getMessage("cutscene-not-found")?.replace("{name}", name) ?: "§cCutscene '$name' not found!"
            player.sendMessage(message)
            return
        }

        val frames = cutscene.frames
        if (frames.isEmpty()) {
            val message = plugin.configManager.getMessage("cutscene-not-found")?.replace("{name}", name) ?: "§cCutscene '$name' has no frames!"
            player.sendMessage(message)
            return
        }

        val durationSeconds = plugin.configManager.config?.getInt("settings.path-visualization.duration", 30) ?: 30

        // Create path visualization session
        val session = PlayerSession.PathVisualization(playerId, name, durationSeconds)
        playerSessions[playerId] = session

        val message = plugin.configManager.getMessage("showing-path")
            ?.replace("{name}", name)
            ?.replace("{duration}", durationSeconds.toString()) ?: "§aShowing path for '$name' ($durationSeconds seconds)..."
        player.sendMessage(message)

        val task = object : BukkitRunnable() {
            var tickCounter = 0
            val totalTicks = durationSeconds * 20

            override fun run() {
                if (tickCounter >= totalTicks) {
                    cancel()
                    playerSessions.remove(playerId)
                    sessionTasks.remove(playerId)
                    return
                }

                for (i in 0 until frames.size - 1) {
                    val start = frames[i].location
                    val end = frames[i + 1].location

                    if (start.world != end.world) {
                        continue
                    }

                    val distance = start.distance(end)
                    val direction = end.toVector().subtract(start.toVector()).normalize()

                    var d = 0.0
                    while (d < distance) {
                        val point = start.toVector().add(direction.clone().multiply(d))
                        start.world.spawnParticle(
                            Particle.END_ROD,
                            point.x, point.y, point.z,
                            1, 0.0, 0.0, 0.0, 0.0
                        )
                        d += 0.5
                    }
                }

                for (frame in frames) {
                    val loc = frame.location
                    loc.world.spawnParticle(
                        Particle.FLAME,
                        loc.x, loc.y, loc.z,
                        3, 0.1, 0.1, 0.1, 0.01
                    )
                }

                tickCounter++
            }
        }.runTaskTimer(plugin, 0L, 5L)

        sessionTasks[playerId] = task
    }

    override fun cancelRecording(player: Player) {
        val playerId = player.uniqueId

        val session = playerSessions[playerId]
        if (session is PlayerSession.Recording) {
            sessionTasks[playerId]?.cancel()
            playerSessions.remove(playerId)
            sessionTasks.remove(playerId)

            val message = plugin.configManager.getMessage("playback-cancelled")?.replace("{name}", session.name) ?: "§cCancelled recording of cutscene '${session.name}'!"
            player.sendMessage(message)
        } else {
            val message = plugin.configManager.getMessage("recording-cancelled") ?: "§7You are not recording anything."
            player.sendMessage(message)
        }
    }

    override fun cancelPlayback(player: Player) {
        val playerId = player.uniqueId

        val session = playerSessions[playerId]
        if (session is PlayerSession.Playback) {
            sessionTasks[playerId]?.cancel()

            // Restore player's game mode
            savedGameModes.remove(playerId)?.let { originalGameMode ->
                player.gameMode = originalGameMode
            }

            // Teleport back to original location if saved
            savedLocations.remove(playerId)?.let { originalLocation ->
                // Ensure the original location chunk is loaded before teleporting back
                if (!originalLocation.world.isChunkLoaded(originalLocation.blockX shr 4, originalLocation.blockZ shr 4)) {
                    originalLocation.world.loadChunk(originalLocation.blockX shr 4, originalLocation.blockZ shr 4, true)
                }
                player.teleport(originalLocation)
            }

            playerSessions.remove(playerId)
            sessionTasks.remove(playerId)

            val message = plugin.configManager.getMessage("playback-cancelled")?.replace("{name}", session.name) ?: "§cCancelled playback of cutscene '${session.name}'!"
            player.sendMessage(message)
        } else {
            val message = plugin.configManager.getMessage("recording-cancelled") ?: "§7You are not watching a cutscene."
            player.sendMessage(message)
        }
    }

    override fun cancelPathVisualization(player: Player) {
        val playerId = player.uniqueId

        val session = playerSessions[playerId]
        if (session is PlayerSession.PathVisualization) {
            sessionTasks[playerId]?.cancel()
            playerSessions.remove(playerId)
            sessionTasks.remove(playerId)
            val message = plugin.configManager.getMessage("path-visualization-cancelled") ?: "§aCancelled path visualization!"
            player.sendMessage(message)
        } else {
            val message = plugin.configManager.getMessage("recording-cancelled") ?: "§7You are not visualizing any path."
            player.sendMessage(message)
        }
    }

    override fun isRecording(player: Player): Boolean {
        val session = playerSessions[player.uniqueId]
        return session is PlayerSession.Recording
    }

    override fun isWatchingCutscene(player: Player): Boolean {
        val session = playerSessions[player.uniqueId]
        return session is PlayerSession.Playback
    }

    override fun getCutsceneNames(): List<String> = cutscenes.keys.toList()

    override fun getCutscene(name: String): Cutscene? = cutscenes[name.lowercase()]

    override fun cancelAllSessions(player: Player) {
        val playerId = player.uniqueId
        val session = playerSessions[playerId]

        when (session) {
            is PlayerSession.Recording -> cancelRecording(player)
            is PlayerSession.Playback -> cancelPlayback(player)
            is PlayerSession.PathVisualization -> cancelPathVisualization(player)
            null -> {
                val message = plugin.configManager.getMessage("nothing-to-cancel") ?: "§7Nothing to cancel."
                player.sendMessage(message)
            }
        }
    }



    override fun cleanup() {
        // Cancel all active session tasks
        for (task in sessionTasks.values) {
            task?.cancel()
        }

        // Save all cutscenes
        for (cutscene in cutscenes.values) {
            saveCutscene(cutscene)
        }

        // Clear all session data
        playerSessions.clear()
        sessionTasks.clear()
        savedInventories.clear()
        savedGameModes.clear()
        savedLocations.clear()
    }
}
