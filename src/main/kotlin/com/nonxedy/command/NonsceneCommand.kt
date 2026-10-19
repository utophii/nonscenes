package com.nonxedy.command

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.configuration.ConfigurationSection
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.entity.Player
import com.nonxedy.Nonscenes
import com.nonxedy.core.ConfigManagerInterface
import com.nonxedy.core.CutsceneManagerInterface
import com.nonxedy.core.CutsceneOpResult
import com.nonxedy.model.timeline.TimelineEvent
import com.nonxedy.model.timeline.TimelineEventType
import com.nonxedy.util.CutsceneNames

class NonsceneCommand(private val plugin: Nonscenes) : CommandExecutor, TabCompleter {
    private val configManager: ConfigManagerInterface by lazy { plugin.configManager }
    private val cutsceneManager: CutsceneManagerInterface by lazy { plugin.cutsceneManager }

    // sender key -> lowercase cutscene name and when the confirmation runs out
    private val pendingDeletes = mutableMapOf<String, Pair<String, Long>>()

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            if (sender is Player) sendHelpMessage(sender)
            else sender.sendMessage(configManager.getMessage("invalid-play-args"))
            return true
        }

        when (args[0].lowercase()) {
            "play" -> handlePlay(sender, args)
            "reload" -> handleReload(sender)
            "event" -> handleEvent(sender, args)
            in PLAYBACK_CONTROLS -> handlePlaybackControl(sender, args)
            in LIBRARY_COMMANDS -> handleLibrary(sender, args)
            in EDITOR_COMMANDS -> {
                if (sender !is Player) {
                    sender.sendMessage(configManager.getMessage("player-only-command"))
                    return true
                }
                if (!sender.hasPermission("nonscene.edit")) {
                    sender.sendMessage(configManager.getMessage("no-permission"))
                    return true
                }
                handleEditor(sender, args)
            }
            "start", "all", "showpath", "stop" -> {
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

    private fun handleReload(sender: CommandSender) {
        if (!sender.hasPermission("nonscene.reload")) {
            sender.sendMessage(configManager.getMessage("no-permission"))
            return
        }
        val storageBefore = storageSettings()
        try {
            configManager.reloadConfigs()
        } catch (e: Exception) {
            plugin.logger.warning("Reload failed, keeping the current config: ${e.message}")
            sender.sendMessage(configManager.getMessage("reload-failed"))
            return
        }
        plugin.triggerService?.reload()
        sender.sendMessage(configManager.getMessage("config-reloaded"))
        // the database connection is only opened on startup
        if (storageSettings() != storageBefore) {
            sender.sendMessage(configManager.getMessage("reload-storage-restart"))
        }
    }

    private fun storageSettings(): Map<String, Any?> =
        configManager.config?.getConfigurationSection("storage")?.getValues(true)
            ?.filterValues { it !is ConfigurationSection }
            .orEmpty()

    private fun handlePlaybackControl(sender: CommandSender, args: Array<String>) {
        val action = args[0].lowercase()
        val permission = if (action == "skip") "nonscene.skip" else "nonscene.pause"
        val target = when {
            args.size >= 2 -> Bukkit.getPlayerExact(args[1]) ?: run {
                sender.sendMessage(configManager.getMessage("player-not-online").replace("{player}", args[1]))
                return
            }
            sender is Player -> sender
            else -> {
                sender.sendMessage(configManager.getMessage("invalid-playback-control-args").replace("{action}", action))
                return
            }
        }
        if (!sender.hasPermission(if (target == sender) permission else "$permission.others")) {
            sender.sendMessage(configManager.getMessage("no-permission"))
            return
        }
        if (!cutsceneManager.isWatchingCutscene(target)) {
            sender.sendMessage(configManager.getMessage("not-watching").replace("{player}", target.name))
            return
        }

        val done = when (action) {
            "skip" -> cutsceneManager.skipPlayback(target)
            "pause" -> cutsceneManager.pausePlayback(target)
            else -> cutsceneManager.resumePlayback(target)
        }
        if (!done) {
            sender.sendMessage(configManager.getMessage("playback-control-unavailable").replace("{action}", action))
            return
        }
        // the viewer gets the usual finished message after a skip
        if (action != "skip") target.sendMessage(configManager.getMessage("playback-$action"))
        if (sender != target) {
            sender.sendMessage(
                configManager.getMessage("playback-$action-other").replace("{player}", target.name)
            )
        }
    }

    private fun handleLibrary(sender: CommandSender, args: Array<String>) {
        val action = args[0].lowercase()
        if (!sender.hasPermission("nonscene.$action")) {
            sender.sendMessage(configManager.getMessage("no-permission"))
            return
        }
        val needed = if (action == "rename" || action == "copy") 3 else 2
        if (args.size < needed) {
            sender.sendMessage(configManager.getMessage("invalid-$action-args"))
            return
        }
        val name = args[1]
        val newName = args.getOrNull(2)

        when (action) {
            "delete" -> handleDelete(sender, name, newName.equals("confirm", ignoreCase = true))
            "rename" -> {
                // looked up before the rename, the triggers still point at the old name
                val references = plugin.triggerService?.references(name).orEmpty()
                val oldName = cutsceneManager.getCutscene(name)?.name ?: name
                val result = cutsceneManager.renameCutscene(name, newName!!)
                if (result != CutsceneOpResult.OK) return sendFailure(sender, result, name, newName)
                sender.sendMessage(
                    configManager.getMessage("cutscene-renamed").replace("{name}", oldName).replace("{new}", newName)
                )
                if (references.isNotEmpty()) {
                    sender.sendMessage(
                        configManager.getMessage("rename-trigger-warning")
                            .replace("{name}", oldName)
                            .replace("{triggers}", references.joinToString(", "))
                    )
                }
            }
            "copy" -> {
                val result = cutsceneManager.copyCutscene(name, newName!!)
                if (result != CutsceneOpResult.OK) return sendFailure(sender, result, name, newName)
                sender.sendMessage(
                    configManager.getMessage("cutscene-copied").replace("{name}", name).replace("{new}", newName)
                )
            }
            "export" -> {
                val result = cutsceneManager.exportCutscene(name)
                if (result != CutsceneOpResult.OK) return sendFailure(sender, result, name, null)
                val stored = cutsceneManager.getCutscene(name)?.name ?: name
                sender.sendMessage(
                    configManager.getMessage("cutscene-exported")
                        .replace("{name}", stored)
                        .replace("{file}", "exports/$stored.yml")
                )
            }
            "import" -> {
                val result = cutsceneManager.importCutscene(name, newName)
                val imported = newName ?: name.removeSuffix(".yml")
                if (result != CutsceneOpResult.OK) return sendFailure(sender, result, name, imported)
                val cutscene = cutsceneManager.getCutscene(imported)
                sender.sendMessage(
                    configManager.getMessage("cutscene-imported")
                        .replace("{name}", cutscene?.name ?: imported)
                        .replace("{frames}", cutscene?.frames?.size?.toString() ?: "0")
                )
                val missingWorld = cutscene?.frames?.firstOrNull { it.resolveLocation() == null }?.worldName
                if (missingWorld != null) {
                    sender.sendMessage(configManager.getMessage("import-world-missing").replace("{world}", missingWorld))
                }
            }
        }
    }

    private fun handleDelete(sender: CommandSender, name: String, confirm: Boolean) {
        val cutscene = cutsceneManager.getCutscene(name)
            ?: return sendFailure(sender, CutsceneOpResult.NOT_FOUND, name, null)
        val key = deleteKey(sender)
        val now = System.currentTimeMillis()
        pendingDeletes.values.removeIf { it.second < now }

        // confirm only counts after the warning
        if (!confirm || pendingDeletes[key]?.first != cutscene.name.lowercase()) {
            pendingDeletes[key] = cutscene.name.lowercase() to now + DELETE_CONFIRM_MS
            sendDeleteConfirmation(sender, cutscene.name)
            return
        }
        pendingDeletes.remove(key)
        val result = cutsceneManager.deleteCutscene(cutscene.name)
        if (result != CutsceneOpResult.OK) return sendFailure(sender, result, cutscene.name, null)
        sender.sendMessage(configManager.getMessage("cutscene-deleted").replace("{name}", cutscene.name))
    }

    private fun sendDeleteConfirmation(sender: CommandSender, name: String) {
        val seconds = (DELETE_CONFIRM_MS / 1000).toString()
        sender.sendMessage(
            configManager.getMessage("delete-confirm").replace("{name}", name).replace("{seconds}", seconds)
        )
        if (sender !is Player) return
        val command = "/nonscene delete $name confirm"
        val button = configManager.getMessageComponent("delete-confirm-button")
            .clickEvent(ClickEvent.runCommand(command))
            .hoverEvent(HoverEvent.showText(Component.text(command)))
        sender.sendMessage(button)
    }

    private fun deleteKey(sender: CommandSender): String =
        (sender as? Player)?.uniqueId?.toString() ?: sender.name

    private fun sendFailure(sender: CommandSender, result: CutsceneOpResult, name: String, newName: String?) {
        val message = when (result) {
            CutsceneOpResult.OK -> return
            CutsceneOpResult.NOT_FOUND -> configManager.getMessage("cutscene-not-found").replace("{name}", name)
            CutsceneOpResult.INVALID_NAME -> configManager.getMessage("invalid-cutscene-name")
            CutsceneOpResult.NAME_TAKEN ->
                configManager.getMessage("cutscene-already-exists").replace("{name}", newName ?: name)
            CutsceneOpResult.IN_USE -> configManager.getMessage("cutscene-in-use").replace("{name}", name)
            CutsceneOpResult.FILE_NOT_FOUND -> configManager.getMessage("import-file-not-found").replace("{file}", name)
            CutsceneOpResult.INVALID_FILE -> configManager.getMessage("import-invalid-file").replace("{file}", name)
            CutsceneOpResult.FAILED -> configManager.getMessage("error-occurred")
        }
        sender.sendMessage(message)
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

    private fun handleEditor(player: Player, args: Array<String>) {
        val editor = plugin.keyframeEditor
        when (args[0].lowercase()) {
            "edit" -> {
                if (args.size < 2) {
                    player.sendMessage(configManager.getMessage("invalid-edit-args"))
                    return
                }
                val tolerance = args.getOrNull(2)?.let {
                    it.toDoubleOrNull()?.takeIf { t -> t > 0.0 } ?: run {
                        player.sendMessage(configManager.getMessage("invalid-edit-args"))
                        return
                    }
                }
                editor.open(player, args[1], tolerance)
            }
            "save" -> editor.save(player)
            "discard" -> editor.discard(player)
            "preview" -> editor.preview(player)
            "key" -> handleKey(player, args)
        }
    }

    private fun handleKey(player: Player, args: Array<String>) {
        val editor = plugin.keyframeEditor
        val action = args.getOrNull(1)?.lowercase()
        val number = args.getOrNull(2)?.toIntOrNull()
        when (action) {
            "add" -> {
                if (args.size >= 3 && number == null) {
                    player.sendMessage(configManager.getMessage("invalid-key-args"))
                    return
                }
                editor.add(player, number)
            }
            "list" -> editor.list(player)
            "remove", "move", "tp" -> {
                if (number == null) {
                    player.sendMessage(configManager.getMessage("invalid-key-args"))
                    return
                }
                when (action) {
                    "remove" -> editor.remove(player, number)
                    "move" -> editor.move(player, number)
                    else -> editor.teleport(player, number)
                }
            }
            "speed" -> {
                val seconds = args.getOrNull(3)?.removeSuffix("s")?.toDoubleOrNull()
                if (number == null || seconds == null) {
                    player.sendMessage(configManager.getMessage("invalid-key-args"))
                    return
                }
                editor.speed(player, number, seconds)
            }
            else -> player.sendMessage(configManager.getMessage("invalid-key-args"))
        }
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
            if (sender.hasPermission("nonscene.skip")) subCommands.add("skip")
            if (sender.hasPermission("nonscene.pause")) subCommands.addAll(listOf("pause", "resume"))
            if (sender.hasPermission("nonscene.reload")) subCommands.add("reload")
            LIBRARY_COMMANDS.filterTo(subCommands) { sender.hasPermission("nonscene.$it") }
            if (sender is Player) {
                if (sender.hasPermission("nonscene.start")) subCommands.add("start")
                if (sender.hasPermission("nonscene.list")) subCommands.add("all")
                if (sender.hasPermission("nonscene.showpath")) subCommands.add("showpath")
                if (sender.hasPermission("nonscene.stop")) subCommands.add("stop")
                if (sender.hasPermission("nonscene.edit")) {
                    subCommands.add("edit")
                    if (plugin.keyframeEditor.isEditing(sender)) {
                        subCommands.addAll(listOf("key", "preview", "save", "discard"))
                    }
                }
            }
            return filterCompletions(subCommands, args[0])
        }

        if (args.size == 2) {
            val subCommand = args[0].lowercase()
            if (subCommand == "play" && sender.hasPermission("nonscene.play")) {
                return filterCompletions(cutsceneManager.getCutsceneNames(), args[1])
            }
            if (subCommand in LIBRARY_COMMANDS && sender.hasPermission("nonscene.$subCommand")) {
                val options = if (subCommand == "import") cutsceneManager.getExportNames()
                else cutsceneManager.getCutsceneNames()
                return filterCompletions(options, args[1])
            }
            if (subCommand in PLAYBACK_CONTROLS) {
                val permission = if (subCommand == "skip") "nonscene.skip.others" else "nonscene.pause.others"
                if (sender.hasPermission(permission)) {
                    return filterCompletions(Bukkit.getOnlinePlayers().map { it.name }, args[1])
                }
            }
            if (sender is Player &&
                subCommand == "showpath" &&
                sender.hasPermission("nonscene.$subCommand")
            ) {
                return filterCompletions(cutsceneManager.getCutsceneNames(), args[1])
            }
        }

        if (sender is Player && sender.hasPermission("nonscene.edit")) {
            val sub = args[0].lowercase()
            if (sub == "edit" && args.size == 2) {
                return filterCompletions(cutsceneManager.getCutsceneNames(), args[1])
            }
            if (sub == "key") {
                if (args.size == 2) {
                    return filterCompletions(listOf("add", "remove", "move", "speed", "tp", "list"), args[1])
                }
                if (args.size == 3 && args[1].lowercase() in KEY_INDEX_ACTIONS) {
                    val count = plugin.keyframeEditor.keyframeCount(sender)
                    val max = when (args[1].lowercase()) {
                        "add" -> count + 1
                        "speed" -> count - 1
                        else -> count
                    }
                    return filterCompletions((1..max).map { it.toString() }, args[2])
                }
            }
        }

        if (args.size == 3 && args[0].equals("delete", ignoreCase = true) &&
            pendingDeletes[deleteKey(sender)]?.first == args[1].lowercase()
        ) {
            return filterCompletions(listOf("confirm"), args[2])
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

    private companion object {
        val EDITOR_COMMANDS = setOf("edit", "key", "save", "discard", "preview")
        val KEY_INDEX_ACTIONS = setOf("add", "remove", "move", "speed", "tp")
        val PLAYBACK_CONTROLS = setOf("skip", "pause", "resume")
        val LIBRARY_COMMANDS = listOf("delete", "rename", "copy", "export", "import")
        const val DELETE_CONFIRM_MS = 30_000L
    }
}
