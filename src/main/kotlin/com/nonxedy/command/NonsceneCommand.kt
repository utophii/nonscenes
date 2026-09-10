package com.nonxedy.command

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import com.nonxedy.Nonscenes
import com.nonxedy.core.ConfigManagerInterface
import com.nonxedy.core.CutsceneManagerInterface
import com.nonxedy.util.CutsceneNames

class NonsceneCommand(private val plugin: Nonscenes) : CommandExecutor, TabCompleter {
    private val configManager: ConfigManagerInterface by lazy { plugin.configManager }
    private val cutsceneManager: CutsceneManagerInterface by lazy { plugin.cutsceneManager }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (sender !is Player) {
            val message = configManager.getMessage("player-only-command")
            sender.sendMessage(message)
            return true
        }

        val player: Player = sender

        // Check if player has permission
        if (!player.hasPermission("nonscene.use")) {
            val message = configManager.getMessage("no-permission")
            player.sendMessage(message)
            return true
        }

        if (args.isEmpty()) {
            sendHelpMessage(player)
            return true
        }

        val subCommand = args[0].lowercase()

        when (subCommand) {
            "start" -> {
                if (!player.hasPermission("nonscene.start")) {
                    val message = configManager.getMessage("no-permission")
                    player.sendMessage(message)
                    return true
                }

                if (args.size < 3) {
                    val message = configManager.getMessage("invalid-start-args")
                    player.sendMessage(message)
                    return true
                }

                val name = args[1]
                if (!CutsceneNames.isValid(name)) {
                    player.sendMessage(configManager.getMessage("invalid-cutscene-name"))
                    return true
                }
                val seconds: Int

                try {
                    seconds = args[2].toInt()
                    if (seconds <= 0 || seconds > 300) {
                        val message = configManager.getMessage("invalid-duration")
                        player.sendMessage(message)
                        return true
                    }
                } catch (e: NumberFormatException) {
                    val message = configManager.getMessage("invalid-duration")
                    player.sendMessage(message)
                    return true
                }

                cutsceneManager.startRecording(player, name, seconds)
            }

            "delete" -> {
                if (!player.hasPermission("nonscene.delete")) {
                    val message = configManager.getMessage("no-permission")
                    player.sendMessage(message)
                    return true
                }

                if (args.size < 2) {
                    val message = configManager.getMessage("specify-cutscene-name")
                    player.sendMessage(message)
                    return true
                }

                cutsceneManager.deleteCutscene(player, args[1])
            }

            "all" -> {
                if (!player.hasPermission("nonscene.list")) {
                    val message = configManager.getMessage("no-permission")
                    player.sendMessage(message)
                    return true
                }

                cutsceneManager.listAllCutscenes(player)
            }

            "play" -> {
                if (!player.hasPermission("nonscene.play")) {
                    val message = configManager.getMessage("no-permission")
                    player.sendMessage(message)
                    return true
                }

                if (args.size < 2) {
                    val message = configManager.getMessage("specify-cutscene-name")
                    player.sendMessage(message)
                    return true
                }

                cutsceneManager.playCutscene(player, args[1])
            }

            "showpath" -> {
                if (!player.hasPermission("nonscene.showpath")) {
                    val message = configManager.getMessage("no-permission")
                    player.sendMessage(message)
                    return true
                }

                if (args.size < 2) {
                    val message = configManager.getMessage("specify-cutscene-name")
                    player.sendMessage(message)
                    return true
                }

                cutsceneManager.showCutscenePath(player, args[1])
            }

            "stop" -> {
                if (!player.hasPermission("nonscene.stop")) {
                    val message = configManager.getMessage("no-permission")
                    player.sendMessage(message)
                    return true
                }

                cutsceneManager.cancelAllSessions(player)
            }

            else -> sendHelpMessage(player)
        }

        return true
    }

    private fun sendHelpMessage(player: Player) {
        val helpMessages = configManager.getMessageList("help-messages")

        helpMessages.forEach { message ->
            player.sendMessage(message)
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): MutableList<String> {
        val completions = mutableListOf<String>()

        if (sender !is Player) {
            return completions
        }

        val player: Player = sender

        if (args.size == 1) {
            val subCommands = mutableListOf<String>()

            if (player.hasPermission("nonscene.start")) subCommands.add("start")
            if (player.hasPermission("nonscene.delete")) subCommands.add("delete")
            if (player.hasPermission("nonscene.list")) subCommands.add("all")
            if (player.hasPermission("nonscene.play")) subCommands.add("play")
            if (player.hasPermission("nonscene.showpath")) subCommands.add("showpath")
            if (player.hasPermission("nonscene.stop")) subCommands.add("stop")

            return filterCompletions(subCommands, args[0])
        } else if (args.size == 2) {
            val subCommand = args[0].lowercase()

            if ((subCommand == "delete" || subCommand == "play" || subCommand == "showpath")
                && player.hasPermission("nonscene.$subCommand")) {
                return filterCompletions(cutsceneManager.getCutsceneNames(), args[1])
            }
        }

        return completions
    }

    private fun filterCompletions(options: List<String>, input: String): MutableList<String> {
        return options.filter { option ->
            option.lowercase().startsWith(input.lowercase())
        }.toMutableList()
    }
}
