package com.nonxedy.command

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import com.nonxedy.Nonscenes
import com.nonxedy.core.ConfigManagerInterface
import com.nonxedy.core.CutsceneManagerInterface
import com.nonxedy.model.timeline.TimelineEvent
import com.nonxedy.model.timeline.TimelineEventType
import com.nonxedy.util.CutsceneNames

class NonsceneCommand(private val plugin: Nonscenes) : CommandExecutor, TabCompleter {
    private val configManager: ConfigManagerInterface by lazy { plugin.configManager }
    private val cutsceneManager: CutsceneManagerInterface by lazy { plugin.cutsceneManager }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            if (sender is Player) sendHelpMessage(sender)
            else sender.sendMessage(configManager.getMessage("invalid-play-args"))
            return true
        }

        when (args[0].lowercase()) {
            "play" -> handlePlay(sender, args)
            "event" -> handleEvent(sender, args)
            "start", "delete", "all", "showpath", "stop" -> {
                if (sender !is Player) {
                    sender.sendMessage(configManager.getMessage("player-only-command"))
                    return true
                }
                if (!sender.hasPermission("nonscene.use")) {
                    sender.sendMessage(configManager.getMessage("no-permission"))
                    return true
                }
                handlePlayerCommand(sender, args)
            }
            else -> {
                if (sender is Player) sendHelpMessage(sender)
                else sender.sendMessage(configManager.getMessage("invalid-play-args"))
            }
        }

        return true
    }

    private fun handlePlay(sender: CommandSender, args: Array<String>) {
        if (!sender.hasPermission("nonscene.play")) {
            sender.sendMessage(configManager.getMessage("no-permission"))
            return
        }

        if (args.size < 2) {
            sender.sendMessage(configManager.getMessage("invalid-play-args"))
            return
        }

        val name = args[1]
        val target: Player? = when {
            args.size >= 3 -> Bukkit.getPlayerExact(args[2])
            sender is Player -> sender
            else -> {
                sender.sendMessage(configManager.getMessage("invalid-play-args"))
                return
            }
        }

        if (target == null) {
            sender.sendMessage(
                configManager.getMessage("player-not-online").replace("{player}", args[2])
            )
            return
        }

        if (target != sender && !sender.hasPermission("nonscene.play.others")) {
            sender.sendMessage(configManager.getMessage("no-permission"))
            return
        }

        if (cutsceneManager.hasActiveSession(target)) {
            sender.sendMessage(configManager.getMessage("already-playing"))
            return
        }

        cutsceneManager.playCutscene(target, name)
        if (sender != target) {
            sender.sendMessage(
                configManager.getMessage("playing-for-player")
                    .replace("{name}", name)
                    .replace("{player}", target.name)
            )
        }
    }

    private fun handleEvent(sender: CommandSender, args: Array<String>) {
        if (!sender.hasPermission("nonscene.event")) {
            sender.sendMessage(configManager.getMessage("no-permission"))
            return
        }
        if (args.size < 3) {
            sender.sendMessage(configManager.getMessage("invalid-event-args"))
            return
        }
        val action = args[1].lowercase()
        val name = args[2]
        val cutscene = cutsceneManager.getCutscene(name)
        if (cutscene == null) {
            sender.sendMessage(configManager.getMessage("cutscene-not-found").replace("{name}", name))
            return
        }
        when (action) {
            "list" -> {
                if (cutscene.events.isEmpty()) {
                    sender.sendMessage(configManager.getMessage("event-list-empty").replace("{name}", cutscene.name))
                    return
                }
                sender.sendMessage(configManager.getMessage("event-list-header").replace("{name}", cutscene.name))
                cutscene.events.forEachIndexed { index, event ->
                    sender.sendMessage(
                        configManager.getMessage("event-list-item")
                            .replace("{index}", index.toString())
                            .replace("{seconds}", (event.timeMs / 1000.0).toString())
                            .replace("{type}", event.type.name.lowercase())
                            .replace("{data}", event.args.values.joinToString(" "))
                    )
                }
            }
            "clear" -> {
                cutsceneManager.clearTimelineEvents(name)
                sender.sendMessage(configManager.getMessage("event-cleared").replace("{name}", cutscene.name))
            }
            "add" -> {
                if (args.size < 6) {
                    sender.sendMessage(configManager.getMessage("invalid-event-args"))
                    return
                }
                val type = runCatching { TimelineEventType.valueOf(args[3].uppercase()) }.getOrNull()
                if (type == null) {
                    sender.sendMessage(configManager.getMessage("invalid-event-type"))
                    return
                }
                val seconds = args[4].toDoubleOrNull()
                if (seconds == null || seconds < 0) {
                    sender.sendMessage(configManager.getMessage("invalid-event-time"))
                    return
                }
                val rest = args.copyOfRange(5, args.size)
                val event = buildEvent(type, (seconds * 1000).toLong(), rest) ?: run {
                    sender.sendMessage(configManager.getMessage("invalid-event-args"))
                    return
                }
                cutsceneManager.addTimelineEvent(name, event)
                sender.sendMessage(
                    configManager.getMessage("event-added")
                        .replace("{name}", cutscene.name)
                        .replace("{type}", type.name.lowercase())
                        .replace("{seconds}", seconds.toString())
                )
            }
            else -> sender.sendMessage(configManager.getMessage("invalid-event-args"))
        }
    }

    private fun buildEvent(type: TimelineEventType, timeMs: Long, rest: Array<String>): TimelineEvent? {
        if (rest.isEmpty()) return null
        val args = linkedMapOf<String, String>()
        when (type) {
            TimelineEventType.TITLE -> {
                val joined = rest.joinToString(" ")
                val parts = joined.split("|", limit = 2)
                args["title"] = parts[0].trim()
                args["subtitle"] = parts.getOrNull(1)?.trim().orEmpty()
            }
            TimelineEventType.SOUND -> {
                args["sound"] = rest[0]
                args["volume"] = rest.getOrNull(1) ?: "1"
                args["pitch"] = rest.getOrNull(2) ?: "1"
            }
            TimelineEventType.PARTICLE -> {
                args["particle"] = rest[0]
                args["count"] = rest.getOrNull(1) ?: "10"
            }
            TimelineEventType.CONSOLE, TimelineEventType.PLAYER -> {
                args["command"] = rest.joinToString(" ")
            }
        }
        return TimelineEvent(timeMs, type, args)
    }

    private fun handlePlayerCommand(player: Player, args: Array<String>) {
        when (args[0].lowercase()) {
            "start" -> {
                if (!player.hasPermission("nonscene.start")) {
                    player.sendMessage(configManager.getMessage("no-permission"))
                    return
                }

                if (args.size < 3) {
                    player.sendMessage(configManager.getMessage("invalid-start-args"))
                    return
                }

                val name = args[1]
                if (!CutsceneNames.isValid(name)) {
                    player.sendMessage(configManager.getMessage("invalid-cutscene-name"))
                    return
                }
                val seconds = args[2].toIntOrNull()
                if (seconds == null || seconds <= 0 || seconds > 300) {
                    player.sendMessage(configManager.getMessage("invalid-duration"))
                    return
                }

                cutsceneManager.startRecording(player, name, seconds)
            }

            "delete" -> {
                if (!player.hasPermission("nonscene.delete")) {
                    player.sendMessage(configManager.getMessage("no-permission"))
                    return
                }

                if (args.size < 2) {
                    player.sendMessage(configManager.getMessage("specify-cutscene-name"))
                    return
                }

                cutsceneManager.deleteCutscene(player, args[1])
            }

            "all" -> {
                if (!player.hasPermission("nonscene.list")) {
                    player.sendMessage(configManager.getMessage("no-permission"))
                    return
                }

                cutsceneManager.listAllCutscenes(player)
            }

            "showpath" -> {
                if (!player.hasPermission("nonscene.showpath")) {
                    player.sendMessage(configManager.getMessage("no-permission"))
                    return
                }
                if (args.size < 2) {
                    player.sendMessage(configManager.getMessage("specify-cutscene-name"))
                    return
                }
                cutsceneManager.showCutscenePath(player, args[1])
            }

            "stop" -> {
                if (!player.hasPermission("nonscene.stop")) {
                    player.sendMessage(configManager.getMessage("no-permission"))
                    return
                }

                cutsceneManager.cancelAllSessions(player)
            }
        }
    }

    private fun sendHelpMessage(player: Player) {
        configManager.getMessageList("help-messages").forEach { message ->
            player.sendMessage(message)
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<String>
    ): MutableList<String> {
        if (args.size == 1) {
            val subCommands = mutableListOf<String>()
            if (sender.hasPermission("nonscene.play")) subCommands.add("play")
            if (sender.hasPermission("nonscene.event")) subCommands.add("event")
            if (sender is Player) {
                if (sender.hasPermission("nonscene.start")) subCommands.add("start")
                if (sender.hasPermission("nonscene.delete")) subCommands.add("delete")
                if (sender.hasPermission("nonscene.list")) subCommands.add("all")
                if (sender.hasPermission("nonscene.showpath")) subCommands.add("showpath")
                if (sender.hasPermission("nonscene.stop")) subCommands.add("stop")
            }
            return filterCompletions(subCommands, args[0])
        }

        if (args.size == 2) {
            val subCommand = args[0].lowercase()
            if (subCommand == "play" && sender.hasPermission("nonscene.play")) {
                return filterCompletions(cutsceneManager.getCutsceneNames(), args[1])
            }
            if (sender is Player &&
                (subCommand == "delete" || subCommand == "showpath") &&
                sender.hasPermission("nonscene.$subCommand")
            ) {
                return filterCompletions(cutsceneManager.getCutsceneNames(), args[1])
            }
        }

        if (args.size == 3 && args[0].equals("play", ignoreCase = true) && sender.hasPermission("nonscene.play.others")) {
            return filterCompletions(Bukkit.getOnlinePlayers().map { it.name }, args[2])
        }

        if (args[0].equals("event", ignoreCase = true) && sender.hasPermission("nonscene.event")) {
            if (args.size == 2) return filterCompletions(listOf("add", "list", "clear"), args[1])
            if (args.size == 3) return filterCompletions(cutsceneManager.getCutsceneNames(), args[2])
            if (args.size == 4 && args[1].equals("add", ignoreCase = true)) {
                return filterCompletions(listOf("title", "sound", "particle", "console", "player"), args[3])
            }
        }

        return mutableListOf()
    }

    private fun filterCompletions(options: List<String>, input: String): MutableList<String> {
        return options.filter { option ->
            option.lowercase().startsWith(input.lowercase())
        }.toMutableList()
    }
}
