package com.nonxedy.core

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.UUID

class SeenStore(plugin: JavaPlugin) {
    private val file = File(plugin.dataFolder, "seen.yml")
    private val yaml = YamlConfiguration()

    init {
        if (file.exists()) yaml.load(file)
    }

    fun hasRegion(playerId: UUID, regionId: String): Boolean {
        return yaml.getStringList("regions.$playerId").contains(regionId)
    }

    fun markRegion(playerId: UUID, regionId: String) {
        val path = "regions.$playerId"
        val current = yaml.getStringList(path).toMutableList()
        if (current.contains(regionId)) return
        current.add(regionId)
        yaml.set(path, current)
        yaml.save(file)
    }
}
