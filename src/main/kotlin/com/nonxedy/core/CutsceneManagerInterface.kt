package com.nonxedy.core

import com.nonxedy.model.Cutscene
import org.bukkit.entity.Player

interface CutsceneManagerInterface {
    fun startRecording(player: Player, name: String, seconds: Int)
    fun playCutscene(player: Player, name: String): Boolean
    fun deleteCutscene(player: Player, name: String)
    fun listAllCutscenes(player: Player)
    fun showCutscenePath(player: Player, name: String)

    fun cancelRecording(player: Player)
    fun cancelPlayback(player: Player)
    fun cancelPathVisualization(player: Player)
    fun cancelAllSessions(player: Player)

    fun hasActiveSession(player: Player): Boolean
    fun isRecording(player: Player): Boolean
    fun isWatchingCutscene(player: Player): Boolean

    fun getCutsceneNames(): List<String>
    fun getCutscene(name: String): Cutscene?

    fun cleanup()
}
