package com.nonxedy

import org.bukkit.command.PluginCommand
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import com.github.retrooper.packetevents.PacketEvents
import com.nonxedy.api.NonscenesAPI
import com.nonxedy.api.NonscenesAPIImpl
import com.nonxedy.command.NonsceneCommand
import com.nonxedy.core.ConfigManager
import com.nonxedy.core.ConfigManagerInterface
import com.nonxedy.core.CutsceneManager
import com.nonxedy.core.CutsceneManagerInterface
import com.nonxedy.core.TriggerService
import com.nonxedy.editor.KeyframeEditor
import com.nonxedy.listener.CommandBlockerListener
import com.nonxedy.listener.PlaybackGuardListener
import com.nonxedy.listener.PlaybackLookLock
import com.nonxedy.listener.PlayerStateRestorer
import dev.faststats.ErrorTracker
import dev.faststats.bukkit.BukkitContext
import dev.faststats.data.Metric
import java.util.Locale
import java.util.logging.Logger

class Nonscenes : JavaPlugin() {
    private val logger = Logger.getLogger("nonscenes")

    // Dependency injection with lateinit var
    lateinit var configManager: ConfigManagerInterface
        private set
    lateinit var cutsceneManager: CutsceneManagerInterface
        private set
    lateinit var api: NonscenesAPI
        private set
    lateinit var keyframeEditor: KeyframeEditor
        private set
    var triggerService: TriggerService? = null
        private set

    private val faststats = BukkitContext.Factory(this, "3e3764a18d41ccfd142e432c2c0e7d26")
        .errorTrackerService(ErrorTracker.contextAware())
        .metrics { factory ->
            factory
                .addMetric(Metric.number("worlds") { server.worlds.size })
                .addMetric(Metric.number("plugins") { server.pluginManager.plugins.size })
                .addMetric(Metric.number("players_online") { server.onlinePlayers.size })
                .addMetric(Metric.number("cutscenes_count") { cutsceneManager.getCutsceneCount() })
                .addMetric(Metric.string("storage_type") {
                    configManager.config?.getString("storage.type")?.uppercase(Locale.ROOT) ?: "SQLITE"
                })
                .addMetric(Metric.string("fade_mode") { configManager.getPlaybackSettings().fade.mode.name })
                .create()
        }
        .create()

    override fun onEnable() {
        try {
            // Initialize dependencies using dependency injection
            initializeDependencies()

            // Register commands
            val nonsceneCommand = NonsceneCommand(this)
            val command: PluginCommand? = getCommand("nonscene")
            command?.apply {
                setExecutor(nonsceneCommand)
                setTabCompleter(nonsceneCommand)
            }

            // Register command blocker listener
            server.pluginManager.registerEvents(CommandBlockerListener(this), this)
            server.pluginManager.registerEvents(PlaybackGuardListener(this), this)
            server.pluginManager.registerEvents(PlayerStateRestorer(this), this)
            PacketEvents.getAPI().eventManager.registerListener(PlaybackLookLock(this))
            server.servicesManager.register(NonscenesAPI::class.java, api, this, ServicePriority.Normal)
            triggerService = TriggerService(this).also { it.register() }
            keyframeEditor = KeyframeEditor(this).also { it.register() }
            faststats.ready()

            logger.info("nonscenes enabled with cutscene functionality")
        } catch (e: Exception) {
            logger.severe("Failed to initialize plugin: ${e.message}")
            logger.severe("Plugin will be disabled")
            server.pluginManager.disablePlugin(this)
        }
    }

    // Initialize all dependencies using dependency injection pattern
    private fun initializeDependencies() {
        requirePacketEvents()

        val configManagerImpl = ConfigManager(this)
        configManagerImpl.loadConfigs()
        configManager = configManagerImpl

        // Initialize cutscene manager with dependency injection
        val cutsceneManagerImpl = CutsceneManager(this)
        cutsceneManager = cutsceneManagerImpl
        api = NonscenesAPIImpl(cutsceneManagerImpl)
    }

    private fun requirePacketEvents() {
        if (server.pluginManager.getPlugin("packetevents") == null) {
            throw IllegalStateException("packetevents is required and must be installed")
        }
        val api = try {
            PacketEvents.getAPI()
        } catch (e: Exception) {
            throw IllegalStateException("PacketEvents API is not available", e)
        }
        if (api == null || !api.isInitialized) {
            throw IllegalStateException("PacketEvents API is not initialized")
        }
    }

    override fun onDisable() {
        faststats.shutdown()
        triggerService?.unregister()
        triggerService = null
        if (::keyframeEditor.isInitialized) {
            keyframeEditor.shutdown()
        }
        if (::api.isInitialized) {
            server.servicesManager.unregister(NonscenesAPI::class.java, api)
        }
        if (::cutsceneManager.isInitialized) {
            cutsceneManager.cleanup()
        }
        logger.info("nonscenes disabled")
    }

    companion object {
        // TODO: replace with the nonscenes project token from faststats.dev
        private const val FASTSTATS_TOKEN = "REPLACE_ME"

        @JvmStatic
        fun getAPI(): NonscenesAPI = getPlugin(Nonscenes::class.java).api
    }
}
