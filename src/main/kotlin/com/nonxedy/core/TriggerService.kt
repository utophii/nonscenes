package com.nonxedy.core

import com.nonxedy.Nonscenes
import com.nonxedy.model.trigger.RegionTrigger
import com.nonxedy.model.trigger.TriggerSettings
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TriggerService(private val plugin: Nonscenes) : Listener {
    private val seen = SeenStore(plugin)
    private val lastRegion = ConcurrentHashMap<UUID, String>()
    private val registered = mutableListOf<Command>()
    val settings: TriggerSettings = loadSettings()

    fun register() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        registerAliasCommands()
    }

    fun unregister() {
        registered.forEach { command ->
            command.unregister(plugin.server.commandMap)
        }
        registered.clear()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        if (!settings.firstJoinEnabled) return
        if (event.player.hasPlayedBefore()) return
        val name = settings.firstJoinCutscene
        if (name.isBlank()) return
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            playIfPossible(event.player, name)
        }, settings.firstJoinDelayTicks.coerceAtLeast(1L))
    }

    @EventHandler(ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (settings.regions.isEmpty()) return
        val from = event.from
        val to = event.to ?: return
        if (from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ) return

        val player = event.player
        val region = settings.regions.firstOrNull {
            it.contains(to.world.name, to.x, to.y, to.z)
        }
        val regionId = region?.id
        val previous = lastRegion.put(player.uniqueId, regionId ?: "")
        if (region == null || region.id == previous) return
        if (region.once && seen.hasRegion(player.uniqueId, region.id)) return
        if (playIfPossible(player, region.cutscene) && region.once) {
            seen.markRegion(player.uniqueId, region.id)
        }
    }

    fun references(cutscene: String): List<String> {
        val refs = mutableListOf<String>()
        if (settings.firstJoinCutscene.equals(cutscene, ignoreCase = true)) refs.add("triggers.first-join")
        settings.commands.forEach { (alias, target) ->
            if (target.equals(cutscene, ignoreCase = true)) refs.add("triggers.commands.$alias")
        }
        settings.regions.forEach { region ->
            if (region.cutscene.equals(cutscene, ignoreCase = true)) refs.add("triggers.regions.${region.id}")
        }
        return refs
    }

    private fun playIfPossible(player: Player, cutscene: String): Boolean {
        if (!player.isOnline) return false
        if (plugin.cutsceneManager.hasActiveSession(player)) return false
        if (plugin.cutsceneManager.getCutscene(cutscene) == null) return false
        return plugin.cutsceneManager.playCutscene(player, cutscene)
    }

    private fun registerAliasCommands() {
        settings.commands.forEach { (alias, cutscene) ->
            val name = alias.lowercase().removePrefix("/")
            if (name.isBlank() || !name.matches(Regex("^[a-z0-9_-]+$"))) return@forEach
            val command = object : Command(name) {
                override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
                    if (sender !is Player) {
                        sender.sendMessage(plugin.configManager.getMessage("player-only-command"))
                        return true
                    }
                    if (plugin.cutsceneManager.hasActiveSession(sender)) return true
                    if (plugin.cutsceneManager.getCutscene(cutscene) == null) {
                        sender.sendMessage(
                            plugin.configManager.getMessage("cutscene-not-found").replace("{name}", cutscene)
                        )
                        return true
                    }
                    plugin.cutsceneManager.playCutscene(sender, cutscene)
                    return true
                }
            }
            command.description = "Play cutscene '$cutscene'"
            command.usage = "/$name"
            plugin.server.commandMap.register("nonscenes", command)
            registered.add(command)
        }
    }

    private fun loadSettings(): TriggerSettings {
        val cfg = plugin.configManager.config ?: return TriggerSettings()
        val commands = linkedMapOf<String, String>()
        cfg.getConfigurationSection("triggers.commands")?.getKeys(false)?.forEach { key ->
            val cutscene = cfg.getString("triggers.commands.$key").orEmpty()
            if (cutscene.isNotBlank()) commands[key] = cutscene
        }
        val regions = mutableListOf<RegionTrigger>()
        cfg.getConfigurationSection("triggers.regions")?.getKeys(false)?.forEach { id ->
            val path = "triggers.regions.$id"
            val cutscene = cfg.getString("$path.cutscene").orEmpty()
            val world = cfg.getString("$path.world").orEmpty()
            if (cutscene.isBlank() || world.isBlank()) return@forEach
            val minX = minOf(cfg.getDouble("$path.min-x"), cfg.getDouble("$path.max-x"))
            val minY = minOf(cfg.getDouble("$path.min-y"), cfg.getDouble("$path.max-y"))
            val minZ = minOf(cfg.getDouble("$path.min-z"), cfg.getDouble("$path.max-z"))
            val maxX = maxOf(cfg.getDouble("$path.min-x"), cfg.getDouble("$path.max-x"))
            val maxY = maxOf(cfg.getDouble("$path.min-y"), cfg.getDouble("$path.max-y"))
            val maxZ = maxOf(cfg.getDouble("$path.min-z"), cfg.getDouble("$path.max-z"))
            regions.add(
                RegionTrigger(
                    id = id,
                    world = world,
                    minX = minX,
                    minY = minY,
                    minZ = minZ,
                    maxX = maxX,
                    maxY = maxY,
                    maxZ = maxZ,
                    cutscene = cutscene,
                    once = cfg.getBoolean("$path.once", true)
                )
            )
        }
        return TriggerSettings(
            firstJoinEnabled = cfg.getBoolean("triggers.first-join.enabled", false),
            firstJoinCutscene = cfg.getString("triggers.first-join.cutscene").orEmpty(),
            firstJoinDelayTicks = cfg.getLong("triggers.first-join.delay-ticks", 40L),
            commands = commands,
            regions = regions
        )
    }
}
