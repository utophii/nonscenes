package com.nonxedy.core

import com.nonxedy.model.editor.EditorSettings
import com.nonxedy.model.playback.PlaybackSettings
import com.nonxedy.model.recording.RecordingSettings
import net.kyori.adventure.text.Component
import org.bukkit.configuration.file.FileConfiguration

interface ConfigManagerInterface {
    val config: FileConfiguration?

    fun loadConfigs()
    fun reloadConfigs()
    fun saveConfig()
    fun saveMessages()

    fun getMessage(path: String): String
    fun getMessageComponent(path: String): Component
    fun getMessageList(path: String): List<String>
    fun getMessageComponentList(path: String): List<Component>
    fun getHelpMessages(): List<String>

    fun getPlaybackSettings(): PlaybackSettings
    fun getRecordingSettings(): RecordingSettings
    fun getEditorSettings(): EditorSettings
}
