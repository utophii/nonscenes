package com.nonxedy

import org.bukkit.command.PluginCommand
import org.bukkit.plugin.java.JavaPlugin
import com.github.retrooper.packetevents.PacketEvents
import com.nonxedy.command.NonsceneCommand
import com.nonxedy.core.ConfigManager
import com.nonxedy.core.ConfigManagerInterface
import com.nonxedy.core.CutsceneManager
import com.nonxedy.core.CutsceneManagerInterface
import com.nonxedy.listener.CommandBlockerListener
import com.nonxedy.listener.PlayerStateRestorer
import java.util.logging.Logger

class Nonscenes : JavaPlugin() {
    private val logger = Logger.getLogger("nonscenes")

    // Dependency injection with lateinit var
    lateinit var configManager: ConfigManagerInterface
        private set
    lateinit var cutsceneManager: CutsceneManagerInterface
        private set

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

            // Register player state restorer listener
            server.pluginManager.registerEvents(PlayerStateRestorer(this), this)

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
        if (::cutsceneManager.isInitialized) {
            cutsceneManager.cleanup()
        }
        logger.info("nonscenes disabled")
    }
}
