package com.nonxedy.core

import com.nonxedy.Nonscenes
import com.nonxedy.database.CutsceneYaml
import com.nonxedy.database.service.CutsceneDatabaseService
import com.nonxedy.database.service.DatabaseType
import com.nonxedy.database.service.PoolSettings
import com.nonxedy.database.service.impl.MongoDBCutsceneDatabaseService
import com.nonxedy.database.service.impl.MySQLCutsceneDatabaseService
import com.nonxedy.database.service.impl.PostgreSQLCutsceneDatabaseService
import com.nonxedy.database.service.impl.SQLiteCutsceneDatabaseService
import com.nonxedy.interpolator.BezierPathInterpolator
import com.nonxedy.interpolator.CatmullRomPathInterpolator
import com.nonxedy.interpolator.PathInterpolator
import com.nonxedy.model.Cutscene
import com.nonxedy.model.CutsceneFrame
import com.nonxedy.model.timeline.TimelineEvent
import com.nonxedy.model.timeline.TimelineEventType
import com.nonxedy.model.playback.InterpolationType
import com.nonxedy.playback.AsyncPacketPlaybackController
import com.nonxedy.playback.CutscenePlaybackController
import com.nonxedy.playback.PathBaker
import com.nonxedy.playback.PlaybackRecovery
import com.nonxedy.recording.CutsceneRecorder
import com.nonxedy.util.CutsceneNames
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.io.IOException
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.logging.Level

class CutsceneManager(private val plugin: Nonscenes) : CutsceneManagerInterface {

    private val databaseService: CutsceneDatabaseService
    private var persistentStorageEnabled: Boolean
    private val cutscenes = mutableMapOf<String, Cutscene>()
    private val playerSessions = ConcurrentHashMap<UUID, PlayerSession>()
    private val sessionTasks = ConcurrentHashMap<UUID, BukkitTask>()
    private val activeControllers = ConcurrentHashMap<UUID, CutscenePlaybackController>()
    private val activeRecorders = ConcurrentHashMap<UUID, CutsceneRecorder>()

    // one camera thread for every viewer
    private val playbackExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "nonscenes-playback").apply { isDaemon = true }
    }
    private val playbackRecovery = PlaybackRecovery(plugin)

    private val cutsceneFolder = File(plugin.dataFolder, "cutscenes")
    private val exportFolder = File(plugin.dataFolder, "exports")

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
                password = config?.getString("storage.mysql.password") ?: "",
                pool = PoolSettings.from(config?.getConfigurationSection("storage.mysql.pool"))
            )
            DatabaseType.POSTGRESQL -> PostgreSQLCutsceneDatabaseService(
                host = config?.getString("storage.postgresql.host") ?: "localhost",
                port = config?.getInt("storage.postgresql.port", 5432) ?: 5432,
                database = config?.getString("storage.postgresql.database") ?: "minecraft",
                schema = config?.getString("storage.postgresql.schema").orEmpty().ifBlank { "public" },
                username = config?.getString("storage.postgresql.username") ?: "postgres",
                password = config?.getString("storage.postgresql.password") ?: "",
                pool = PoolSettings.from(config?.getConfigurationSection("storage.postgresql.pool"))
            )
            DatabaseType.MONGODB -> {
                val host = config?.getString("storage.mongodb.host") ?: "localhost"
                val port = config?.getInt("storage.mongodb.port", 27017) ?: 27017
                val database = config?.getString("storage.mongodb.database") ?: "minecraft"
                val username = config?.getString("storage.mongodb.username").orEmpty()
                val password = config?.getString("storage.mongodb.password").orEmpty()
                val collection = config?.getString("storage.mongodb.collection").orEmpty().ifBlank { "cutscenes" }
                val credentials = if (username.isNotBlank()) "$username:$password@" else ""
                MongoDBCutsceneDatabaseService("mongodb://$credentials$host:$port", database, collection)
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

    // files in cutscenes/ are the store only while the database is unavailable.
    // With a database each file is migrated once and renamed to *.yml.migrated
    private fun loadCutscenesFromFiles() {
        if (!ensureCutsceneFolderExists()) return
        val files = cutsceneFolder.listFiles { _, name -> name.endsWith(".yml") } ?: return
        var count = 0
        for (file in files) {
            val name = file.nameWithoutExtension
            if (!CutsceneNames.isValid(name)) {
                plugin.logger.warning("Skipping cutscene with invalid name: ${file.name}")
                continue
            }
            try {
                val fromFile = CutsceneYaml.read(file, name)
                val key = name.lowercase()
                val existing = cutscenes[key]
                if (existing != null) {
                    // databases from before timeline events only have them in the file
                    if (existing.events.isEmpty() && fromFile.events.isNotEmpty()) {
                        val updated = existing.copy(events = fromFile.events)
                        if (persistentStorageEnabled) databaseService.saveCutscene(updated)
                        cutscenes[key] = updated
                    }
                    if (persistentStorageEnabled) markMigrated(file)
                    continue
                }
                if (fromFile.frames.isEmpty()) continue
                if (persistentStorageEnabled) {
                    databaseService.saveCutscene(fromFile)
                    plugin.logger.info("Migrated cutscene from file to database: $name")
                    markMigrated(file)
                }
                cutscenes[key] = fromFile
                count++
            } catch (e: Exception) {
                plugin.logger.log(Level.WARNING, "Failed to load cutscene from file: ${file.name}", e)
            }
        }
        if (count > 0) plugin.logger.info("Loaded $count cutscenes from files")
    }

    private fun markMigrated(file: File) {
        val target = File(file.parentFile, "${file.name}.migrated")
        if (target.exists()) target.delete()
        if (!file.renameTo(target)) {
            plugin.logger.warning("Could not rename migrated cutscene file: ${file.absolutePath}")
        }
    }

    private fun saveCutscene(cutscene: Cutscene) {
        if (persistentStorageEnabled) {
            databaseService.saveCutscene(cutscene)
            return
        }
        if (!ensureCutsceneFolderExists()) throw IOException("Cutscene directory unavailable")
        CutsceneYaml.write(cutscene, cutsceneFile(cutscene.name))
    }

    private fun deleteStoredCutscene(name: String) {
        if (persistentStorageEnabled) databaseService.deleteCutscene(name)
        val file = cutsceneFile(name)
        if (file.exists() && !file.delete()) throw IOException("Failed to delete cutscene file: ${file.absolutePath}")
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
    override fun playCutscene(player: Player, name: String): Boolean {
        val cutscene = cutscenes[name.lowercase()]
        if (cutscene == null || cutscene.frames.isEmpty()) {
            player.sendMessage(plugin.configManager.getMessage("cutscene-not-found")?.replace("{name}", name) ?: "§cNot found")
            return false
        }
        return startPlayback(player, cutscene, name)
    }

    // plays an unsaved cutscene. Command events are skipped so a preview never
    // runs console or player commands
    override fun previewCutscene(player: Player, cutscene: Cutscene): Boolean {
        val safeEvents = cutscene.events.filter {
            it.type != TimelineEventType.CONSOLE && it.type != TimelineEventType.PLAYER
        }
        return startPlayback(player, cutscene.copy(events = safeEvents), cutscene.name)
    }

    private fun startPlayback(player: Player, cutscene: Cutscene, name: String): Boolean {
        val playerId = player.uniqueId
        if (playerSessions.containsKey(playerId)) {
            player.sendMessage(plugin.configManager.getMessage("already-playing") ?: "")
            return false
        }
        if (cutscene.frames.size < 2) {
            player.sendMessage("§cCutscene '$name' needs at least two frames.")
            return false
        }

        val missingWorld = findFirstMissingWorldName(cutscene.frames)
        if (missingWorld != null) {
            player.sendMessage("§cUnable to play cutscene '$name': world '$missingWorld' is not loaded.")
            return false
        }

        val settings = plugin.configManager.getPlaybackSettings()
        val path = try {
            PathBaker.bake(cutscene, settings, createInterpolator(settings.interpolation))
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Failed to bake path for '$name'", e)
            player.sendMessage(plugin.configManager.getMessage("error-occurred") ?: "§cFailed to play cutscene.")
            return false
        }

        if (path.isEmpty()) {
            player.sendMessage("§cCutscene '$name' produced an empty path.")
            return false
        }

        val totalDurationMs = cutscene.totalDurationMs
        playerSessions[playerId] = PlayerSession.Playback(playerId, name, 0, path.size)
        player.sendMessage(plugin.configManager.getMessage("cutscene-playing")?.replace("{name}", name) ?: "§aPlaying...")
        player.updateCommands()

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

        preloadChunks(path).whenComplete { _, error ->
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (error != null) {
                    plugin.logger.warning("Chunk preload for '$name' did not finish cleanly: ${error.message}")
                }
                if (!player.isOnline || playerSessions[playerId] !is PlayerSession.Playback) {
                    return@Runnable
                }
                val controller = AsyncPacketPlaybackController(
                    plugin,
                    playbackExecutor,
                    playbackRecovery,
                    settings.updateRate,
                    settings.eyeHeight,
                    settings.fade,
                    onComplete,
                    onCancel,
                    cutscene.events,
                    name
                )
                activeControllers[playerId] = controller
                controller.start(player, path, totalDurationMs)
            })
        }
        return true
    }

    override fun createInterpolator(type: InterpolationType): PathInterpolator = when (type) {
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
        Bukkit.getPlayer(playerId)?.updateCommands()
    }

    private fun preloadChunks(frames: List<Location>): CompletableFuture<Void> {
        val byWorld = frames.mapNotNull { loc -> loc.world?.let { it to loc } }.groupBy({ it.first }, { it.second })
        if (byWorld.isEmpty()) return CompletableFuture.completedFuture(null)

        val futures = byWorld.flatMap { (world, locs) ->
            val chunks = linkedSetOf<Pair<Int, Int>>()
            locs.forEach { f ->
                val cx = f.blockX shr 4
                val cz = f.blockZ shr 4
                for (dx in -2..2) for (dz in -2..2) chunks.add((cx + dx) to (cz + dz))
            }
            plugin.logger.info("Preloading ${chunks.size} chunks in '${world.name}' for cutscene...")
            chunks.map { (cx, cz) -> loadChunk(world, cx, cz) }
        }
        return CompletableFuture.allOf(*futures.toTypedArray())
            .orTimeout(10, TimeUnit.SECONDS)
            .exceptionally { null }
    }

    private fun loadChunk(world: World, cx: Int, cz: Int): CompletableFuture<*> {
        if (world.isChunkLoaded(cx, cz)) {
            return CompletableFuture.completedFuture(null)
        }
        return try {
            world.getChunkAtAsync(cx, cz)
        } catch (_: NoSuchMethodError) {
            val future = CompletableFuture<Void>()
            plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                world.loadChunk(cx, cz, false)
                future.complete(null)
            })
            future
        }
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
        val curve = sampleCurve(cutscene)

        val task = object : BukkitRunnable() {
            var elapsedTicks = 0
            val periodTicks = 5
            val totalTicks = durationSeconds * 20
            override fun run() {
                if (elapsedTicks >= totalTicks) {
                    cancel(); playerSessions.remove(playerId); sessionTasks.remove(playerId); return
                }
                for (i in 0 until curve.size - 1) {
                    val start = curve[i]
                    val end = curve[i + 1]
                    if (start.world != end.world) continue
                    val distance = start.distance(end)
                    if (distance <= 0.0) continue
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

    // the spline the camera follows, sampled densely enough to draw
    override fun sampleCurve(cutscene: Cutscene, stepMs: Long): List<Location> {
        if (cutscene.frames.isEmpty()) return emptyList()
        val curve = createInterpolator(plugin.configManager.getPlaybackSettings().interpolation).curve(cutscene)
        val total = cutscene.totalDurationMs
        val steps = (total / stepMs.coerceAtLeast(1L)).toInt().coerceIn(1, MAX_CURVE_SAMPLES)
        return (0..steps).mapNotNull { i ->
            val point = curve.sample(total * i.toDouble() / steps)
            val world = Bukkit.getWorld(point.worldName) ?: return@mapNotNull null
            Location(world, point.x, point.y, point.z, point.yaw, point.pitch)
        }
    }

    // Deletion / Listing
    override fun deleteCutscene(name: String): CutsceneOpResult {
        val normalized = name.lowercase()
        val cutscene = cutscenes[normalized] ?: return CutsceneOpResult.NOT_FOUND
        if (isInUse(cutscene.name)) return CutsceneOpResult.IN_USE
        try {
            deleteStoredCutscene(cutscene.name)
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Failed to delete cutscene: ${cutscene.name}", e)
            return CutsceneOpResult.FAILED
        }
        cutscenes.remove(normalized)
        return CutsceneOpResult.OK
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

    override fun pausePlayback(player: Player): Boolean =
        activeControllers[player.uniqueId]?.pause() ?: false

    override fun resumePlayback(player: Player): Boolean =
        activeControllers[player.uniqueId]?.resume() ?: false

    override fun skipPlayback(player: Player): Boolean =
        activeControllers[player.uniqueId]?.skip() ?: false

    override fun isPlaybackPaused(player: Player): Boolean =
        activeControllers[player.uniqueId]?.isPaused() ?: false

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
    override fun getCutsceneCount(): Int = cutscenes.size
    override fun getCutscene(name: String): Cutscene? = cutscenes[name.lowercase()]

    // a name is taken by a stored cutscene, a recording that will be saved under
    // it, or an open editor draft
    private fun isNameTaken(name: String): Boolean {
        if (cutscenes.containsKey(name.lowercase())) return true
        val recording = playerSessions.values.any {
            (it is PlayerSession.RecordingCountdown || it is PlayerSession.Recording) &&
                it.name.equals(name, ignoreCase = true)
        }
        return recording || plugin.keyframeEditor.isEditingCutscene(name)
    }

    private fun isInUse(name: String): Boolean =
        playerSessions.values.any { it.name.equals(name, ignoreCase = true) } ||
            plugin.keyframeEditor.isEditingCutscene(name)

    override fun renameCutscene(name: String, newName: String): CutsceneOpResult {
        val cutscene = cutscenes[name.lowercase()] ?: return CutsceneOpResult.NOT_FOUND
        if (!CutsceneNames.isValid(newName)) return CutsceneOpResult.INVALID_NAME
        if (cutscene.name == newName) return CutsceneOpResult.OK
        val caseOnly = cutscene.name.equals(newName, ignoreCase = true)
        if (!caseOnly && isNameTaken(newName)) return CutsceneOpResult.NAME_TAKEN
        if (isInUse(cutscene.name)) return CutsceneOpResult.IN_USE

        val renamed = cutscene.copy(name = newName)
        try {
            if (caseOnly) {
                // a case-insensitive database matches the new name to the old row
                deleteStoredCutscene(cutscene.name)
                try {
                    saveCutscene(renamed)
                } catch (e: Exception) {
                    runCatching { saveCutscene(cutscene) }
                    throw e
                }
            } else {
                saveCutscene(renamed)
                try {
                    deleteStoredCutscene(cutscene.name)
                } catch (e: Exception) {
                    runCatching { deleteStoredCutscene(newName) }
                    throw e
                }
            }
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Failed to rename cutscene ${cutscene.name} to $newName", e)
            return CutsceneOpResult.FAILED
        }
        cutscenes.remove(cutscene.name.lowercase())
        cutscenes[newName.lowercase()] = renamed
        return CutsceneOpResult.OK
    }

    override fun copyCutscene(name: String, newName: String): CutsceneOpResult {
        val cutscene = cutscenes[name.lowercase()] ?: return CutsceneOpResult.NOT_FOUND
        if (!CutsceneNames.isValid(newName)) return CutsceneOpResult.INVALID_NAME
        if (isNameTaken(newName)) return CutsceneOpResult.NAME_TAKEN
        val copy = cutscene.copy(name = newName)
        try {
            saveCutscene(copy)
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Failed to copy cutscene ${cutscene.name} to $newName", e)
            return CutsceneOpResult.FAILED
        }
        cutscenes[newName.lowercase()] = copy
        return CutsceneOpResult.OK
    }

    override fun exportCutscene(name: String): CutsceneOpResult {
        val cutscene = cutscenes[name.lowercase()] ?: return CutsceneOpResult.NOT_FOUND
        try {
            if (!exportFolder.isDirectory && !exportFolder.mkdirs()) {
                throw IOException("Export directory unavailable: ${exportFolder.absolutePath}")
            }
            CutsceneYaml.write(cutscene, fileIn(exportFolder, cutscene.name))
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Failed to export cutscene ${cutscene.name}", e)
            return CutsceneOpResult.FAILED
        }
        return CutsceneOpResult.OK
    }

    override fun importCutscene(fileName: String, newName: String?): CutsceneOpResult {
        val file = findExportFile(fileName.removeSuffix(".yml")) ?: return CutsceneOpResult.FILE_NOT_FOUND
        val name = newName ?: file.nameWithoutExtension
        if (!CutsceneNames.isValid(name)) return CutsceneOpResult.INVALID_NAME
        if (isNameTaken(name)) return CutsceneOpResult.NAME_TAKEN

        val cutscene = try {
            CutsceneYaml.read(file, name)
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Failed to read cutscene file ${file.name}", e)
            return CutsceneOpResult.INVALID_FILE
        }
        if (cutscene.frames.size < 2) return CutsceneOpResult.INVALID_FILE
        try {
            saveCutscene(cutscene)
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Failed to import cutscene $name", e)
            return CutsceneOpResult.FAILED
        }
        cutscenes[name.lowercase()] = cutscene
        return CutsceneOpResult.OK
    }

    override fun getExportNames(): List<String> =
        exportFolder.listFiles { _, n -> n.endsWith(".yml") }
            ?.map { it.nameWithoutExtension }
            ?.filter { CutsceneNames.isValid(it) }
            .orEmpty()

    override fun saveEditedCutscene(cutscene: Cutscene) {
        val key = cutscene.name.lowercase()
        val previous = cutscenes[key]
        cutscenes[key] = cutscene
        try {
            saveCutscene(cutscene)
        } catch (e: Exception) {
            if (previous != null) cutscenes[key] = previous else cutscenes.remove(key)
            throw e
        }
    }

    override fun addTimelineEvent(name: String, event: TimelineEvent): Boolean {
        val key = name.lowercase()
        val cutscene = cutscenes[key] ?: return false
        val updated = cutscene.copy(events = (cutscene.events + event).sortedBy { it.timeMs })
        cutscenes[key] = updated
        saveCutscene(updated)
        return true
    }

    override fun clearTimelineEvents(name: String): Boolean {
        val key = name.lowercase()
        val cutscene = cutscenes[key] ?: return false
        if (cutscene.events.isEmpty()) return true
        val updated = cutscene.copy(events = emptyList())
        cutscenes[key] = updated
        saveCutscene(updated)
        return true
    }

    // Helpers
    private fun cutsceneFile(name: String): File = fileIn(cutsceneFolder, name)

    private fun fileIn(folder: File, name: String): File {
        if (!CutsceneNames.isValid(name)) {
            throw IOException("Invalid cutscene name: $name")
        }
        val file = File(folder, "$name.yml")
        if (file.canonicalFile.parentFile != folder.canonicalFile) {
            throw IOException("Cutscene path escaped storage directory: $name")
        }
        return file
    }

    private fun findExportFile(name: String): File? {
        if (!CutsceneNames.isValid(name)) return null
        val exact = fileIn(exportFolder, name)
        if (exact.isFile) return exact
        return exportFolder.listFiles { _, n -> n.endsWith(".yml") }
            ?.firstOrNull { it.nameWithoutExtension.equals(name, ignoreCase = true) }
    }

    private companion object {
        const val MAX_CURVE_SAMPLES = 4000
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
        playbackExecutor.shutdownNow()
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