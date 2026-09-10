package com.nonxedy.core

import com.nonxedy.Nonscenes
import com.nonxedy.model.playback.InterpolationType
import com.nonxedy.model.playback.PlaybackSettings
import com.nonxedy.model.recording.RecordingSettings
import com.nonxedy.util.ColorUtil
import net.kyori.adventure.text.Component
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.logging.Level

class ConfigManager(private val plugin: Nonscenes) : ConfigManagerInterface {
    override var config: FileConfiguration? = null
        private set
    var messages: FileConfiguration? = null
        private set
    private var configFile: File? = null
    private var messagesFile: File? = null

    override fun loadConfigs() {
        if (!plugin.dataFolder.exists()) {
            plugin.dataFolder.mkdirs()
        }

        val configFileLocal = File(plugin.dataFolder, "config.yml")
        configFile = configFileLocal
        if (!configFileLocal.exists()) {
            plugin.saveResource("config.yml", false)
        }
        config = YamlConfiguration.loadConfiguration(configFileLocal)

        var configUpdated = false
        val defaultConfigStream = plugin.getResource("config.yml")
        if (defaultConfigStream != null) {
            val defaultConfig = YamlConfiguration.loadConfiguration(
                InputStreamReader(defaultConfigStream, StandardCharsets.UTF_8)
            )
            for (key in defaultConfig.getKeys(true)) {
                if (!requireNotNull(config).contains(key)) {
                    requireNotNull(config).set(key, defaultConfig.get(key))
                    configUpdated = true
                }
            }
        }
        if (configUpdated) {
            try {
                config?.save(requireNotNull(configFile) { "Config file is null" })
            } catch (e: IOException) {
                plugin.logger.log(Level.WARNING, "Could not save updated config.yml", e)
            }
        }

        val messagesFileLocal = File(plugin.dataFolder, "messages.yml")
        messagesFile = messagesFileLocal
        if (!messagesFileLocal.exists()) {
            plugin.saveResource("messages.yml", false)
        }
        messages = YamlConfiguration.loadConfiguration(messagesFileLocal)

        var messagesUpdated = false
        val defaultMessagesStream = plugin.getResource("messages.yml")
        if (defaultMessagesStream != null) {
            val defaultMessages = YamlConfiguration.loadConfiguration(
                InputStreamReader(defaultMessagesStream, StandardCharsets.UTF_8)
            )
            for (key in defaultMessages.getKeys(true)) {
                if (!requireNotNull(messages).contains(key)) {
                    requireNotNull(messages).set(key, defaultMessages.get(key))
                    messagesUpdated = true
                }
            }
        }
        if (messagesUpdated) {
            try {
                messages?.save(requireNotNull(messagesFile) { "Messages file is null" })
            } catch (e: IOException) {
                plugin.logger.log(Level.WARNING, "Could not save updated messages.yml", e)
            }
        }
    }

    override fun getMessage(path: String): String {
        var message = messages?.getString(path, "Missing message: $path") ?: "Missing message: $path"
        if (message.contains("\${prefix}")) {
            val prefix = messages?.getString("prefix", "&8[&bnonscenes&8] &r") ?: "&8[&bnonscenes&8] &r"
            message = message.replace("\${prefix}", prefix)
        }
        return ColorUtil.format(message)
    }

    override fun getMessageComponent(path: String): Component {
        var message = messages?.getString(path, "Missing message: $path") ?: "Missing message: $path"
        if (message.contains("\${prefix}")) {
            val prefix = messages?.getString("prefix", "&8[&bnonscenes&8] &r") ?: "&8[&bnonscenes&8] &r"
            message = message.replace("\${prefix}", prefix)
        }
        return ColorUtil.toComponent(message)
    }

    override fun getMessageList(path: String): List<String> {
        val messageList = messages?.getStringList(path) ?: emptyList()
        return messageList.map { ColorUtil.format(it) }
    }

    override fun getMessageComponentList(path: String): List<Component> {
        val messageList = messages?.getStringList(path) ?: emptyList()
        return messageList.map { ColorUtil.toComponent(it) }
    }

    override fun getHelpMessages(): List<String> = getMessageList("help-messages")

    override fun reloadConfigs() {
        config = configFile?.let { YamlConfiguration.loadConfiguration(it) }
        messages = messagesFile?.let { YamlConfiguration.loadConfiguration(it) }
        val defaultConfigStream = plugin.getResource("config.yml")
        if (defaultConfigStream != null) {
            val defaultConfig = YamlConfiguration.loadConfiguration(
                InputStreamReader(defaultConfigStream, StandardCharsets.UTF_8)
            )
            config?.setDefaults(defaultConfig)
        }
        val defaultMessagesStream = plugin.getResource("messages.yml")
        if (defaultMessagesStream != null) {
            val defaultMessages = YamlConfiguration.loadConfiguration(
                InputStreamReader(defaultMessagesStream, StandardCharsets.UTF_8)
            )
            messages?.setDefaults(defaultMessages)
        }
    }

    override fun saveConfig() {
        try {
            config?.save(requireNotNull(configFile) { "Config file is null" })
        } catch (e: IOException) {
            plugin.logger.log(Level.SEVERE, "Could not save config to $configFile", e)
        }
    }

    override fun saveMessages() {
        try {
            val currentMessagesFile = messagesFile
            val currentMessages = messages
            if (currentMessagesFile != null && currentMessages != null) {
                currentMessages.save(currentMessagesFile)
            }
        } catch (e: IOException) {
            plugin.logger.log(Level.SEVERE, "Could not save messages to $messagesFile", e)
        }
    }

    override fun getPlaybackSettings(): PlaybackSettings {
        val cfg = config ?: return PlaybackSettings()
        val interpolation = runCatching {
            InterpolationType.valueOf(cfg.getString("cutscene.playback.interpolation", "CATMULL_ROM")!!.uppercase())
        }.getOrDefault(InterpolationType.CATMULL_ROM)

        val legacyMode = cfg.getString("cutscene.playback.mode")
        if (legacyMode != null && !legacyMode.equals("ASYNC_PACKET", ignoreCase = true)) {
            plugin.logger.warning(
                "cutscene.playback.mode=$legacyMode is ignored; playback is ASYNC_PACKET only"
            )
        }

        return PlaybackSettings(
            updateRate = cfg.getInt("cutscene.playback.update-rate", 60).coerceIn(20, 240),
            interpolation = interpolation,
            smoothRotation = cfg.getBoolean("cutscene.playback.smooth-rotation", true),
            bakePath = cfg.getBoolean("cutscene.playback.bake-path", true),
            rideHeightOffset = cfg.getDouble("cutscene.playback.ride-height-offset", 0.75)
        )
    }

    override fun getRecordingSettings(): RecordingSettings {
        val cfg = config ?: return RecordingSettings()
        val color = runCatching {
            BarColor.valueOf(cfg.getString("cutscene.creation.progress-bar.color", "BLUE")!!.uppercase())
        }.getOrDefault(BarColor.BLUE)

        val style = runCatching {
            BarStyle.valueOf(cfg.getString("cutscene.creation.progress-bar.style", "SOLID")!!.uppercase())
        }.getOrDefault(BarStyle.SOLID)

        return RecordingSettings(
            progressBarEnabled = cfg.getBoolean("cutscene.creation.progress-bar.enabled", true),
            barStyle = style,
            barColor = color,
            captureIntervalTicks = cfg.getInt("cutscene.recording.capture-interval-ticks", 1).coerceAtLeast(1)
        )
    }
}
