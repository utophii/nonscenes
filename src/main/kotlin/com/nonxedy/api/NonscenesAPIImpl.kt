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
}
