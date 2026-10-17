package com.nonxedy.core

import com.nonxedy.interpolator.PathInterpolator
import com.nonxedy.model.Cutscene
import com.nonxedy.model.playback.InterpolationType
import com.nonxedy.model.timeline.TimelineEvent
import org.bukkit.Location
import org.bukkit.entity.Player

interface CutsceneManagerInterface {
    fun startRecording(player: Player, name: String, seconds: Int)
    fun playCutscene(player: Player, name: String): Boolean
    fun previewCutscene(player: Player, cutscene: Cutscene): Boolean
    fun deleteCutscene(player: Player, name: String)
    fun listAllCutscenes(player: Player)
    fun showCutscenePath(player: Player, name: String)

    fun cancelRecording(player: Player)
    fun cancelPlayback(player: Player)
    fun cancelPathVisualization(player: Player)
    fun cancelAllSessions(player: Player)

    fun pausePlayback(player: Player): Boolean
    fun resumePlayback(player: Player): Boolean
    fun skipPlayback(player: Player): Boolean
    fun isPlaybackPaused(player: Player): Boolean

    fun hasActiveSession(player: Player): Boolean
    fun isRecording(player: Player): Boolean
    fun isWatchingCutscene(player: Player): Boolean

    fun getCutsceneNames(): List<String>
    fun getCutscene(name: String): Cutscene?
    fun addTimelineEvent(name: String, event: TimelineEvent): Boolean
    fun clearTimelineEvents(name: String): Boolean
    fun saveEditedCutscene(cutscene: Cutscene)

    fun renameCutscene(name: String, newName: String): CutsceneOpResult
    fun copyCutscene(name: String, newName: String): CutsceneOpResult
    fun exportCutscene(name: String): CutsceneOpResult
    fun importCutscene(fileName: String, newName: String?): CutsceneOpResult
    fun getExportNames(): List<String>

    fun createInterpolator(type: InterpolationType): PathInterpolator
    fun sampleCurve(cutscene: Cutscene, stepMs: Long = 50L): List<Location>

    fun cleanup()
}
