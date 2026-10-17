package com.nonxedy.api

import com.nonxedy.core.CutsceneManagerInterface
import org.bukkit.entity.Player

internal class NonscenesAPIImpl(
    private val cutscenes: CutsceneManagerInterface
) : NonscenesAPI {

    override fun play(player: Player, name: String): Boolean {
        return cutscenes.playCutscene(player, name)
    }

    override fun stop(player: Player): Boolean {
        if (!cutscenes.isWatchingCutscene(player)) return false
        cutscenes.cancelPlayback(player)
        return true
    }

    override fun isPlaying(player: Player): Boolean {
        return cutscenes.isWatchingCutscene(player)
    }

    override fun pause(player: Player): Boolean = cutscenes.pausePlayback(player)

    override fun resume(player: Player): Boolean = cutscenes.resumePlayback(player)

    override fun isPaused(player: Player): Boolean = cutscenes.isPlaybackPaused(player)

    override fun skip(player: Player): Boolean = cutscenes.skipPlayback(player)
}
