package com.nonxedy.core

import com.nonxedy.Nonscenes
import com.nonxedy.database.service.CutsceneDatabaseService
import com.nonxedy.database.service.DatabaseType
import com.nonxedy.database.service.impl.MongoDBCutsceneDatabaseService
import com.nonxedy.database.service.impl.MySQLCutsceneDatabaseService
import com.nonxedy.database.service.impl.PostgreSQLCutsceneDatabaseService
import com.nonxedy.database.service.impl.SQLiteCutsceneDatabaseService
import com.nonxedy.interpolator.BezierPathInterpolator
import com.nonxedy.interpolator.CatmullRomPathInterpolator
import com.nonxedy.interpolator.PathInterpolator
import com.nonxedy.model.Cutscene
import com.nonxedy.model.CutsceneFrame
import com.nonxedy.model.playback.InterpolationType
import com.nonxedy.playback.AsyncPacketPlaybackController
import com.nonxedy.playback.CutscenePlaybackController
import com.nonxedy.playback.PathBaker
import com.nonxedy.recording.CutsceneRecorder
import com.nonxedy.util.CutsceneNames
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.io.IOException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

class CutsceneManager(private val plugin: Nonscenes) : CutsceneManagerInterface {

    private val databaseService: CutsceneDatabaseService
    private var persistentStorageEnabled: Boolean
    private val cutscenes = mutableMapOf<String, Cutscene>()
    private val playerSessions = ConcurrentHashMap<UUID, PlayerSession>()
    private val sessionTasks = ConcurrentHashMap<UUID, BukkitTask>()
    private val activeControllers = ConcurrentHashMap<UUID, CutscenePlaybackController>()
    private val activeRecorders = ConcurrentHashMap<UUID, CutsceneRecorder>()

    private val cutsceneFolder = File(plugin.dataFolder, "cutscenes")

    init {
        databaseService = createDatabaseService()
        try {
            databaseService.initialize()
            persistentStorageEnabled = true
            loadCutscenesFromDatabase()
            loadCutscenesFromFiles()
        } catch (e: Exception) {
            persistentStorageEnabled = false
            plugin.logger.log(Level.SEVERE, "Failed to initialize database, falling back to file storage", e)
            loadCutscenesFromFiles()
        }
    }

    // Database / File bootstrap
    private fun createDatabaseService(): CutsceneDatabaseService {
        val config = plugin.configManager.config
        val typeName = config?.getString("storage.type")?.uppercase(Locale.ROOT) ?: DatabaseType.SQLITE.name
        val type = runCatching { DatabaseType.valueOf(typeName) }.getOrElse {
            plugin.logger.warning("Unknown storage type '$typeName', falling back to SQLITE")
            DatabaseType.SQLITE
        }
        return when (type) {
            DatabaseType.SQLITE -> {
                val filePath = config?.getString("storage.sqlite.file-path").orEmpty().ifBlank { "cutscenes.db" }
                SQLiteCutsceneDatabaseService(File(plugin.dataFolder, filePath))
            }
            DatabaseType.MYSQL -> MySQLCutsceneDatabaseService(
                host = config?.getString("storage.mysql.host") ?: "localhost",
                port = config?.getInt("storage.mysql.port", 3306) ?: 3306,
                database = config?.getString("storage.mysql.database") ?: "minecraft",
                username = config?.getString("storage.mysql.username") ?: "root",
                password = config?.getString("storage.mysql.password") ?: ""
            )
            DatabaseType.POSTGRESQL -> PostgreSQLCutsceneDatabaseService(
                host = config?.getString("storage.postgresql.host") ?: "localhost",
                port = config?.getInt("storage.postgresql.port", 5432) ?: 5432,
                database = config?.getString("storage.postgresql.database") ?: "minecraft",
                username = config?.getString("storage.postgresql.username") ?: "postgres",
                password = config?.getString("storage.postgresql.password") ?: ""
            )
            DatabaseType.MONGODB -> {
                val host = config?.getString("storage.mongodb.host") ?: "localhost"
                val port = config?.getInt("storage.mongodb.port", 27017) ?: 27017
                val database = config?.getString("storage.mongodb.database") ?: "minecraft"
                val username = config?.getString("storage.mongodb.username").orEmpty()
                val password = config?.getString("storage.mongodb.password").orEmpty()
                val credentials = if (username.isNotBlank()) "$username:$password@" else ""
                MongoDBCutsceneDatabaseService("mongodb://$credentials$host:$port", database)
            }
        }
    }

    private fun loadCutscenesFromDatabase() {
        try {
            val loaded = databaseService.loadAllCutscenes()
            for (c in loaded) {
                if (!CutsceneNames.isValid(c.name)) {
                    plugin.logger.warning("Skipping cutscene with invalid name from database: ${c.name}")
                    continue
                }
                cutscenes[c.name.lowercase()] = c
            }
            plugin.logger.info("Loaded ${loaded.size} cutscenes from database")
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Failed to load cutscenes from database", e)
        }
    }

    private fun ensureCutsceneFolderExists(): Boolean {
        if (cutsceneFolder.exists()) return cutsceneFolder.isDirectory || false
        return cutsceneFolder.mkdirs()
    }

    private fun loadCutscenesFromFiles() {
        if (!ensureCutsceneFolderExists()) return
        val files = cutsceneFolder.listFiles { _, name -> name.endsWith(".yml") } ?: return
        var count = 0
        for (file in files) {
            try {
                val config = YamlConfiguration.loadConfiguration(file)
                val name = file.nameWithoutExtension
                if (!CutsceneNames.isValid(name)) {
                    plugin.logger.warning("Skipping cutscene with invalid name: ${file.name}")
                    continue
                }
                if (cutscenes.containsKey(name.lowercase())) continue
                val frames = mutableListOf<CutsceneFrame>()
                val framesSection = config.getConfigurationSection("frames")
                val frameDurationMs = loadFrameDurationMs(config)
                if (framesSection != null) {
                    for (key in framesSection.getKeys(false)) {
                        val sec = framesSection.getConfigurationSection(key) ?: continue
                        val worldName = sec.getString("world") ?: continue
                        val world = Bukkit.getWorld(worldName) ?: continue
                        val loc = Location(
                            world,
                            sec.getDouble("x"),
                            sec.getDouble("y"),
                            sec.getDouble("z"),
                            sec.getDouble("yaw").toFloat(),
                            sec.getDouble("pitch").toFloat()
                        )
                        frames.add(CutsceneFrame(loc, worldName))
                    }
                }
                if (frames.isNotEmpty()) {
                    val cutscene = Cutscene(name, frames, frameDurationMs)
                    cutscenes[name.lowercase()] = cutscene
                    count++
                    if (persistentStorageEnabled) {
                        try {
                            databaseService.saveCutscene(cutscene)
                            plugin.logger.info("Migrated cutscene from file to database: $name")
                        } catch (dbEx: Exception) {
                            plugin.logger.log(Level.WARNING, "Failed to migrate cutscene to database: $name", dbEx)
                        }
                    }
                }
            } catch (e: Exception) {
                plugin.logger.warning("Failed to load cutscene from file: ${file.name}")
            }
        }
        if (count > 0) plugin.logger.info("Loaded $count cutscenes from files")
    }

    private fun saveCutscene(cutscene: Cutscene) {
        if (persistentStorageEnabled) databaseService.saveCutscene(cutscene)
        saveCutsceneToFile(cutscene)
    }

    private fun saveCutsceneToFile(cutscene: Cutscene) {
        if (!ensureCutsceneFolderExists()) throw IOException("Cutscene directory unavailable")
        if (!CutsceneNames.isValid(cutscene.name)) {
            throw IOException("Invalid cutscene name: ${cutscene.name}")
        }
        val file = cutsceneFile(cutscene.name)
        val config = YamlConfiguration()
        config.set("name", cutscene.name)
        config.set("frame-duration-ms", cutscene.frameDurationMs)
        config.set("ticks-per-frame", cutscene.ticksPerFrame)
        config.set("schema-version", 1)
        for (i in cutscene.frames.indices) {
            val f = cutscene.frames[i]
            config.set("frames.$i.world", f.worldName)
            config.set("frames.$i.x", f.location.x)
            config.set("frames.$i.y", f.location.y)
            config.set("frames.$i.z", f.location.z)
            config.set("frames.$i.yaw", f.location.yaw)
            config.set("frames.$i.pitch", f.location.pitch)
        }
        config.save(file)
    }

    private fun loadFrameDurationMs(config: FileConfiguration): Long {
        val stored = config.getLong("frame-duration-ms", -1L)
        if (stored > 0L) return stored
        val legacy = config.getInt("ticks-per-frame", 1).coerceAtLeast(1)
        return legacy * 50L
    }

    // Recording
    override fun startRecording(player: Player, name: String, seconds: Int) {
        if (!CutsceneNames.isValid(name)) {
            player.sendMessage(plugin.configManager.getMessage("invalid-cutscene-name"))
            return
        }
        val playerId = player.uniqueId
        if (playerSessions.containsKey(playerId)) {
            player.sendMessage(plugin.configManager.getMessage("already-recording"))
            return
        }
        if (cutscenes.containsKey(name.lowercase())) {
            player.sendMessage(
                plugin.configManager.getMessage("cutscene-already-exists")?.replace("{name}", name)
                    ?: "§cA cutscene with that name already exists!"
            )
            return
        }

        val countdownSeconds = plugin.configManager.config?.getInt("settings.countdown-seconds", 3) ?: 3
        player.sendMessage(
            plugin.configManager.getMessage("recording-countdown")?.replace("{seconds}", countdownSeconds.toString())
                ?: "§aRecording will start in $countdownSeconds seconds..."
        )
        playerSessions[playerId] = PlayerSession.RecordingCountdown(playerId, name, seconds, countdownSeconds)

        val task = object : BukkitRunnable() {
            var remaining = countdownSeconds
            override fun run() {
                if (!player.isOnline || playerSessions[playerId] !is PlayerSession.RecordingCountdown) {
                    cancel(); sessionTasks.remove(playerId); return
                }
                if (remaining > 0) {
                    playerSessions[playerId] = PlayerSession.RecordingCountdown(playerId, name, seconds, remaining)
                    player.sendMessage(
                        plugin.configManager.getMessage("countdown")?.replace("{seconds}", remaining.toString()) ?: "§e$remaining..."
                    )
                    remaining--
                } else {
                    cancel(); sessionTasks.remove(playerId); startRecordingProcess(player, name, seconds)
                }
            }
        }.runTaskTimer(plugin, 0L, 20L)
        sessionTasks[playerId] = task
    }

    private fun startRecordingProcess(player: Player, name: String, durationSeconds: Int) {
        if (!player.isOnline) return
        val playerId = player.uniqueId
        val settings = plugin.configManager.getRecordingSettings()
        playerSessions[playerId] = PlayerSession.Recording(playerId, name, 0)
        player.sendMessage(plugin.configManager.getMessage("recording-started")?.replace("{name}", name) ?: "§aStarted recording cutscene '$name'!")

        val recorder = CutsceneRecorder(
            plugin = plugin,
            settings = settings,
            onComplete = { cutscene ->
                cutscenes[cutscene.name.lowercase()] = cutscene
                try {
                    saveCutscene(cutscene)
                } catch (e: Exception) {
                    cutscenes.remove(cutscene.name.lowercase())
                    plugin.logger.log(Level.SEVERE, "Failed to persist cutscene: ${cutscene.name}", e)
                    player.sendMessage(plugin.configManager.getMessage("error-occurred") ?: "§cFailed to save cutscene.")
                    playerSessions.remove(playerId)
                    activeRecorders.remove(playerId)
                    return@CutsceneRecorder
                }
                player.sendMessage(
                    plugin.configManager.getMessage("recording-finished")
                        ?.replace("{name}", cutscene.name)
                        ?.replace("{frames}", cutscene.frames.size.toString())
                        ?: "§aFinished recording cutscene '${cutscene.name}' with ${cutscene.frames.size} frames!"
                )
                playerSessions.remove(playerId)
                activeRecorders.remove(playerId)
            },
            onCancel = {
                player.sendMessage(plugin.configManager.getMessage("playback-cancelled")?.replace("{name}", name) ?: "§cRecording cancelled.")
                playerSessions.remove(playerId)
                activeRecorders.remove(playerId)
            }
        )
        activeRecorders[playerId] = recorder
        recorder.start(player, name, durationSeconds)
    }

    // Playback
    override fun playCutscene(player: Player, name: String) {
        val playerId = player.uniqueId
        if (playerSessions.containsKey(playerId)) {
            player.sendMessage(plugin.configManager.getMessage("already-playing") ?: "")
            return
        }
        val cutscene = cutscenes[name.lowercase()]
        if (cutscene == null || cutscene.frames.isEmpty()) {
            player.sendMessage(plugin.configManager.getMessage("cutscene-not-found")?.replace("{name}", name) ?: "§cNot found")
            return
        }

        val resolvedFrames = resolveFrameLocations(cutscene.frames)
        if (resolvedFrames == null) {
            val worldName = findFirstMissingWorldName(cutscene.frames) ?: "unknown"
            player.sendMessage("§cUnable to play cutscene '$name': world '$worldName' is not loaded.")
            return
        }

        val settings = plugin.configManager.getPlaybackSettings()
        val interpolator = createInterpolator(settings.interpolation)

        val path = if (settings.bakePath) {
            try {
                PathBaker.bake(cutscene.copy(frames = resolvedFrames.map { CutsceneFrame(it) }), settings, interpolator)
            } catch (e: Exception) {
                plugin.logger.warning("Failed to bake path for '$name', falling back to raw frames: ${e.message}")
                resolvedFrames
            }
        } else resolvedFrames

        if (path.isEmpty()) {
            player.sendMessage("§cCutscene '$name' produced an empty path.")
            return
        }

        val totalDurationMs = cutscene.frameDurationMs * (cutscene.frames.size - 1)
        playerSessions[playerId] = PlayerSession.Playback(playerId, name, 0, path.size)
        player.sendMessage(plugin.configManager.getMessage("cutscene-playing")?.replace("{name}", name) ?: "§aPlaying...")

        player.teleport(path[0])
        preloadChunksAsync(path)

        val onComplete = {
            Bukkit.getScheduler().runTask(plugin, Runnable {
                val p = Bukkit.getPlayer(playerId)
                if (p != null) {
                    val session = playerSessions[playerId]
                    if (session is PlayerSession.Playback) {
                        finishPlayback(p, session.name)
                    }
                } else {
                    cleanupSession(playerId)
                }
            })
            Unit
        }
        val onCancel = {
            Bukkit.getScheduler().runTask(plugin, Runnable {
                cleanupSession(playerId)
            })
            Unit
        }

        val controller = AsyncPacketPlaybackController(
            plugin,
            settings.updateRate,
            settings.rideHeightOffset,
            onComplete,
            onCancel
        )
        activeControllers[playerId] = controller
        controller.start(player, path, totalDurationMs)
    }

    private fun createInterpolator(type: InterpolationType): PathInterpolator = when (type) {
        InterpolationType.CATMULL_ROM -> CatmullRomPathInterpolator()
        InterpolationType.BEZIER -> BezierPathInterpolator()
    }

    private fun finishPlayback(player: Player, name: String) {
        player.sendMessage(plugin.configManager.getMessage("cutscene-playback-finished")?.replace("{name}", name) ?: "§aFinished playing cutscene '$name'!")
        cleanupSession(player.uniqueId)
    }

    private fun cleanupSession(playerId: UUID) {
        playerSessions.remove(playerId)
        sessionTasks.remove(playerId)?.cancel()
        activeControllers.remove(playerId)?.stop()
        activeRecorders.remove(playerId)?.cancel()
    }

    private fun preloadChunksAsync(frames: List<Location>) {
        val world = frames.firstOrNull()?.world ?: return
        val chunks = mutableSetOf<Pair<Int, Int>>()
        frames.forEach { f ->
            val cx = f.blockX shr 4
            val cz = f.blockZ shr 4
            for (dx in -2..2) for (dz in -2..2) chunks.add((cx + dx) to (cz + dz))
        }
        chunks.forEach { (cx, cz) ->
            if (!world.isChunkLoaded(cx, cz)) {
                try {
                    world.getChunkAtAsync(cx, cz)
                } catch (_: NoSuchMethodError) {
                    plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable { world.loadChunk(cx, cz, false) })
                }
            }
        }
        plugin.logger.info("Preloading ${chunks.size} chunks for cutscene...")
    }

    // Path visualization
    override fun showCutscenePath(player: Player, name: String) {
        val playerId = player.uniqueId
        if (playerSessions.containsKey(playerId)) {
            player.sendMessage(plugin.configManager.getMessage("path-already-showing") ?: "§cYou are already visualizing a path!")
            return
        }
        val cutscene = cutscenes[name.lowercase()]
        if (cutscene == null || cutscene.frames.isEmpty()) {
            player.sendMessage(plugin.configManager.getMessage("cutscene-not-found")?.replace("{name}", name) ?: "§cNot found")
            return
        }
        val durationSeconds = plugin.configManager.config?.getInt("settings.path-visualization.duration", 30) ?: 30
        val particleName = plugin.configManager.config?.getString("settings.path-visualization.particle", "END_ROD")?.replace(" ", "_")?.uppercase() ?: "END_ROD"
        val pathParticle = try { Particle.valueOf(particleName) } catch (_: IllegalArgumentException) { Particle.END_ROD }
        val spacing = plugin.configManager.config?.getDouble("settings.path-visualization.spacing", 0.5) ?: 0.5

        playerSessions[playerId] = PlayerSession.PathVisualization(playerId, name, durationSeconds)
        player.sendMessage(plugin.configManager.getMessage("showing-path")?.replace("{name}", name)?.replace("{duration}", durationSeconds.toString()) ?: "§aShowing path for '$name'...")

        val resolvedFrames = resolveFrameLocations(cutscene.frames)
        if (resolvedFrames == null) {
            player.sendMessage("§cUnable to show path for '$name': world is not loaded.")
            playerSessions.remove(playerId)
            return
        }

        val task = object : BukkitRunnable() {
            var elapsedTicks = 0
            val periodTicks = 5
            val totalTicks = durationSeconds * 20
            override fun run() {
                if (elapsedTicks >= totalTicks) {
                    cancel(); playerSessions.remove(playerId); sessionTasks.remove(playerId); return
                }
                for (i in 0 until resolvedFrames.size - 1) {
                    val start = resolvedFrames[i]
                    val end = resolvedFrames[i + 1]
                    if (start.world != end.world) continue
                    val distance = start.distance(end)
                    val direction = end.toVector().subtract(start.toVector()).normalize()
                    var d = 0.0
                    while (d < distance) {
                        val point = start.toVector().add(direction.clone().multiply(d))
                        start.world.spawnParticle(pathParticle, point.x, point.y, point.z, 1, 0.0, 0.0, 0.0, 0.0)
                        d += spacing
                    }
                }
                for (loc in resolvedFrames) {
                    loc.world.spawnParticle(Particle.FLAME, loc.x, loc.y, loc.z, 3, 0.1, 0.1, 0.1, 0.01)
                }
                elapsedTicks += periodTicks
            }
        }.runTaskTimer(plugin, 0L, 5L)
        sessionTasks[playerId] = task
    }

    // Deletion / Listing
    override fun deleteCutscene(player: Player, name: String) {
        val normalized = name.lowercase()
        val cutscene = cutscenes[normalized]
        if (cutscene == null) {
            player.sendMessage(plugin.configManager.getMessage("cutscene-not-found")?.replace("{name}", name) ?: "§cNot found")
            return
        }
        val file = cutsceneFile(cutscene.name)
        if (file.exists() && !file.delete()) {
            plugin.logger.warning("Failed to delete cutscene file: ${file.absolutePath}")
            player.sendMessage(plugin.configManager.getMessage("error-occurred") ?: "§cFailed to delete.")
            return
        }
        if (persistentStorageEnabled) {
            try { databaseService.deleteCutscene(cutscene.name) } catch (e: Exception) {
                plugin.logger.log(Level.SEVERE, "Failed to delete from database: ${cutscene.name}", e)
                player.sendMessage(plugin.configManager.getMessage("error-occurred") ?: "§cFailed to delete.")
                return
            }
        }
        cutscenes.remove(normalized)
        player.sendMessage(plugin.configManager.getMessage("cutscene-deleted")?.replace("{name}", name) ?: "§aDeleted.")
    }

    override fun listAllCutscenes(player: Player) {
        if (cutscenes.isEmpty()) {
            player.sendMessage(plugin.configManager.getMessage("no-cutscenes") ?: "§7No cutscenes found.")
            return
        }
        player.sendMessage(plugin.configManager.getMessage("cutscene-list-header") ?: "§6=== Available Cutscenes ===")
        for ((_, c) in cutscenes) {
            player.sendMessage(
                plugin.configManager.getMessage("cutscene-list-item")
                    ?.replace("{name}", c.name)
                    ?.replace("{frames}", c.frames.size.toString())
                    ?: "§7- §f${c.name} §7(${c.frames.size} frames)"
            )
        }
    }

    // Cancellation
    override fun cancelRecording(player: Player) {
        val playerId = player.uniqueId
        val session = playerSessions[playerId]
        if (session is PlayerSession.RecordingCountdown || session is PlayerSession.Recording) {
            sessionTasks[playerId]?.cancel()
            activeRecorders[playerId]?.cancel()
            cleanupSession(playerId)
            player.sendMessage(plugin.configManager.getMessage("playback-cancelled")?.replace("{name}", session.name) ?: "§cCancelled.")
        } else {
            player.sendMessage(plugin.configManager.getMessage("recording-cancelled") ?: "§7You are not recording anything.")
        }
    }

    override fun cancelPlayback(player: Player) {
        val playerId = player.uniqueId
        val session = playerSessions[playerId]
        if (session is PlayerSession.Playback) {
            activeControllers[playerId]?.stop()
            cleanupSession(playerId)
            player.sendMessage(plugin.configManager.getMessage("playback-cancelled")?.replace("{name}", session.name) ?: "§cCancelled.")
        } else {
            player.sendMessage(plugin.configManager.getMessage("recording-cancelled") ?: "§7You are not watching a cutscene.")
        }
    }

    override fun cancelPathVisualization(player: Player) {
        val playerId = player.uniqueId
        val session = playerSessions[playerId]
        if (session is PlayerSession.PathVisualization) {
            sessionTasks[playerId]?.cancel()
            cleanupSession(playerId)
            player.sendMessage(plugin.configManager.getMessage("path-visualization-cancelled") ?: "§aCancelled.")
        } else {
            player.sendMessage(plugin.configManager.getMessage("recording-cancelled") ?: "§7You are not visualizing any path.")
        }
    }

    override fun cancelAllSessions(player: Player) {
        when (playerSessions[player.uniqueId]) {
            is PlayerSession.RecordingCountdown, is PlayerSession.Recording -> cancelRecording(player)
            is PlayerSession.Playback -> cancelPlayback(player)
            is PlayerSession.PathVisualization -> cancelPathVisualization(player)
            null -> player.sendMessage(plugin.configManager.getMessage("nothing-to-cancel") ?: "§7Nothing to cancel.")
        }
    }

    // Queries
    override fun isRecording(player: Player): Boolean {
        return when (playerSessions[player.uniqueId]) {
            is PlayerSession.RecordingCountdown, is PlayerSession.Recording -> true
            else -> false
        }
    }

    override fun hasActiveSession(player: Player): Boolean = playerSessions.containsKey(player.uniqueId)
    override fun isWatchingCutscene(player: Player): Boolean = playerSessions[player.uniqueId] is PlayerSession.Playback
    override fun getCutsceneNames(): List<String> = cutscenes.keys.toList()
    override fun getCutscene(name: String): Cutscene? = cutscenes[name.lowercase()]

    // Helpers
    private fun cutsceneFile(name: String): File {
        if (!CutsceneNames.isValid(name)) {
            throw IOException("Invalid cutscene name: $name")
        }
        val file = File(cutsceneFolder, "$name.yml")
        val folder = cutsceneFolder.canonicalFile
        val resolved = file.canonicalFile
        if (resolved.parentFile != folder) {
            throw IOException("Cutscene path escaped storage directory: $name")
        }
        return file
    }

    private fun resolveFrameLocations(frames: List<CutsceneFrame>): List<Location>? {
        return frames.map { it.resolveLocation() ?: return null }
    }

    private fun findFirstMissingWorldName(frames: List<CutsceneFrame>): String? {
        return frames.firstOrNull { it.resolveLocation() == null }?.worldName
    }

    override fun cleanup() {
        for (task in sessionTasks.values) task?.cancel()
        for (controller in activeControllers.values) controller.stop()
        for (recorder in activeRecorders.values) recorder.cancel()
        for (cutscene in cutscenes.values) {
            try { saveCutscene(cutscene) } catch (e: Exception) {
                plugin.logger.log(Level.WARNING, "Failed to persist during cleanup: ${cutscene.name}", e)
            }
        }
        if (persistentStorageEnabled) databaseService.shutdown()
        playerSessions.clear()
        sessionTasks.clear()
        activeControllers.clear()
        activeRecorders.clear()
    }
}
