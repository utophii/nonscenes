package com.nonxedy.database

import com.nonxedy.model.Cutscene
import com.nonxedy.model.CutsceneFrame
import com.nonxedy.model.timeline.TimelineEvents
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

// the single-file cutscene format, used by the file fallback storage and by
// export / import. Frames whose world is not loaded keep their world name and
// resolve once it is
object CutsceneYaml {

    const val SCHEMA_VERSION = 1

    fun write(cutscene: Cutscene, file: File) {
        toYaml(cutscene).save(file)
    }

    fun toYaml(cutscene: Cutscene): YamlConfiguration {
        val config = YamlConfiguration()
        config.set("name", cutscene.name)
        config.set("frame-duration-ms", cutscene.frameDurationMs)
        config.set("ticks-per-frame", cutscene.ticksPerFrame)
        config.set("schema-version", SCHEMA_VERSION)
        for (i in cutscene.frames.indices) {
            val f = cutscene.frames[i]
            config.set("frames.$i.world", f.worldName)
            config.set("frames.$i.x", f.location.x)
            config.set("frames.$i.y", f.location.y)
            config.set("frames.$i.z", f.location.z)
            config.set("frames.$i.yaw", f.location.yaw)
            config.set("frames.$i.pitch", f.location.pitch)
            f.segmentMs?.let { config.set("frames.$i.segment-ms", it) }
        }
        TimelineEvents.writeYaml(config, cutscene.events)
        return config
    }

    fun read(file: File, name: String, worlds: (String) -> World? = Bukkit::getWorld): Cutscene {
        return fromYaml(YamlConfiguration.loadConfiguration(file), name, worlds)
    }

    fun fromYaml(config: YamlConfiguration, name: String, worlds: (String) -> World? = Bukkit::getWorld): Cutscene {
        val frames = mutableListOf<CutsceneFrame>()
        val framesSection = config.getConfigurationSection("frames")
        if (framesSection != null) {
            for (key in framesSection.getKeys(false).sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }) {
                val sec = framesSection.getConfigurationSection(key) ?: continue
                val worldName = sec.getString("world") ?: continue
                val loc = Location(
                    worlds(worldName),
                    sec.getDouble("x"),
                    sec.getDouble("y"),
                    sec.getDouble("z"),
                    sec.getDouble("yaw").toFloat(),
                    sec.getDouble("pitch").toFloat()
                )
                val segmentMs = sec.getLong("segment-ms", -1L).takeIf { it > 0L }
                frames.add(CutsceneFrame(loc, worldName, segmentMs))
            }
        }
        return Cutscene(name, frames, frameDurationMs(config), TimelineEvents.readYaml(config))
    }

    private fun frameDurationMs(config: YamlConfiguration): Long {
        val stored = config.getLong("frame-duration-ms", -1L)
        if (stored > 0L) return stored
        val legacy = config.getInt("ticks-per-frame", 1).coerceAtLeast(1)
        return legacy * 50L
    }
}
