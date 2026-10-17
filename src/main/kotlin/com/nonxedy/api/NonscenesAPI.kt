package com.nonxedy.api

import org.bukkit.entity.Player

interface NonscenesAPI {
    fun play(player: Player, name: String): Boolean

    fun stop(player: Player): Boolean

    fun isPlaying(player: Player): Boolean

    fun pause(player: Player): Boolean

    fun resume(player: Player): Boolean

    fun isPaused(player: Player): Boolean

    fun skip(player: Player): Boolean
}
