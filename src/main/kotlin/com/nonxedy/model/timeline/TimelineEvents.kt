package com.nonxedy.model.timeline

import com.nonxedy.util.ColorUtil
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import java.time.Duration

object TimelineEvents {

    fun fire(player: Player, cutsceneName: String, event: TimelineEvent) {
        val args = event.args.mapValues { (_, value) -> interpolate(value, player, cutsceneName) }
        when (event.type) {
            TimelineEventType.TITLE -> {
                val title = text(args["title"].orEmpty())
                val subtitle = text(args["subtitle"].orEmpty())
                val fadeIn = args["fade-in"]?.toLongOrNull() ?: 10L
                val stay = args["stay"]?.toLongOrNull() ?: 40L
                val fadeOut = args["fade-out"]?.toLongOrNull() ?: 10L
                player.showTitle(
                    Title.title(
                        title,
                        subtitle,
                        Title.Times.times(
                            Duration.ofMillis(fadeIn * 50L),
                            Duration.ofMillis(stay * 50L),
                            Duration.ofMillis(fadeOut * 50L)
                        )
                    )
                )
            }
            TimelineEventType.SOUND -> {
                val name = args["sound"].orEmpty()
                val volume = args["volume"]?.toFloatOrNull() ?: 1f
                val pitch = args["pitch"]?.toFloatOrNull() ?: 1f
                val enumName = name.replace('.', '_').replace('-', '_').uppercase()
                try {
                    player.playSound(player.location, Sound.valueOf(enumName), volume, pitch)
                } catch (_: IllegalArgumentException) {
                    player.playSound(player.location, name, volume, pitch)
                }
            }
            TimelineEventType.PARTICLE -> {
                val name = args["particle"].orEmpty().replace('.', '_').uppercase()
                val count = args["count"]?.toIntOrNull()?.coerceAtLeast(1) ?: 10
                val particle = try {
                    Particle.valueOf(name)
                } catch (_: IllegalArgumentException) {
                    Particle.FLAME
                }
                player.spawnParticle(particle, player.location, count, 0.3, 0.3, 0.3, 0.01)
            }
            TimelineEventType.CONSOLE -> {
                val command = args["command"].orEmpty().removePrefix("/")
                if (command.isNotBlank()) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)
                }
            }
            TimelineEventType.PLAYER -> {
                val command = args["command"].orEmpty().removePrefix("/")
                if (command.isNotBlank()) {
                    player.performCommand(command)
                }
            }
        }
    }

    fun writeYaml(config: FileConfiguration, events: List<TimelineEvent>) {
        events.forEachIndexed { index, event ->
            config.set("events.$index.time-ms", event.timeMs)
            config.set("events.$index.type", event.type.name)
            event.args.forEach { (key, value) ->
                config.set("events.$index.$key", value)
            }
        }
    }

    fun readYaml(config: FileConfiguration): List<TimelineEvent> {
        val section = config.getConfigurationSection("events") ?: return emptyList()
        return readSection(section)
    }

    fun encode(events: List<TimelineEvent>): String {
        return events.joinToString("\n") { event ->
            val payload = event.args.entries.joinToString("\u0001") { "${it.key}=${it.value.replace("\n", " ")}" }
            "${event.type.name}\t${event.timeMs}\t$payload"
        }
    }

    fun decode(raw: String?): List<TimelineEvent> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            if (parts.size < 2) return@mapNotNull null
            val type = runCatching { TimelineEventType.valueOf(parts[0].uppercase()) }.getOrNull()
                ?: return@mapNotNull null
            val timeMs = parts[1].toLongOrNull() ?: return@mapNotNull null
            val args = linkedMapOf<String, String>()
            if (parts.size == 3 && parts[2].isNotEmpty()) {
                parts[2].split('\u0001').forEach { pair ->
                    val eq = pair.indexOf('=')
                    if (eq > 0) args[pair.substring(0, eq)] = pair.substring(eq + 1)
                }
            }
            TimelineEvent(timeMs, type, args)
        }.toList()
    }

    fun fromMongo(documents: List<org.bson.Document>?): List<TimelineEvent> {
        if (documents.isNullOrEmpty()) return emptyList()
        return documents.mapNotNull { doc ->
            val type = runCatching { TimelineEventType.valueOf(doc.getString("type").uppercase()) }.getOrNull()
                ?: return@mapNotNull null
            val timeMs = (doc.get("timeMs") as? Number)?.toLong() ?: return@mapNotNull null
            val args = linkedMapOf<String, String>()
            doc.forEach { (key, value) ->
                if (key != "type" && key != "timeMs" && value != null) args[key] = value.toString()
            }
            TimelineEvent(timeMs, type, args)
        }
    }

    fun toMongo(events: List<TimelineEvent>): List<org.bson.Document> {
        return events.map { event ->
            val doc = org.bson.Document("type", event.type.name).append("timeMs", event.timeMs)
            event.args.forEach { (k, v) -> doc.append(k, v) }
            doc
        }
    }

    private fun readSection(section: ConfigurationSection): List<TimelineEvent> {
        return section.getKeys(false).mapNotNull { key ->
            val sec = section.getConfigurationSection(key) ?: return@mapNotNull null
            val type = runCatching {
                TimelineEventType.valueOf(sec.getString("type", "TITLE")!!.uppercase())
            }.getOrNull() ?: return@mapNotNull null
            val timeMs = sec.getLong("time-ms", 0L).coerceAtLeast(0L)
            val args = linkedMapOf<String, String>()
            for (argKey in sec.getKeys(false)) {
                if (argKey == "type" || argKey == "time-ms") continue
                val value = sec.get(argKey) ?: continue
                args[argKey] = value.toString()
            }
            TimelineEvent(timeMs, type, args)
        }.sortedBy { it.timeMs }
    }

    private fun interpolate(value: String, player: Player, cutsceneName: String): String {
        return value
            .replace("{player}", player.name)
            .replace("{uuid}", player.uniqueId.toString())
            .replace("{world}", player.world.name)
            .replace("{cutscene}", cutsceneName)
    }

    private fun text(raw: String) = if (raw.contains('<')) {
        MiniMessage.miniMessage().deserialize(raw)
    } else {
        ColorUtil.toComponent(raw)
    }
}
