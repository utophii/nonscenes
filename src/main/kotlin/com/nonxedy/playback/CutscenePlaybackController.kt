package com.nonxedy.playback

import org.bukkit.Location
import org.bukkit.entity.Player

interface CutscenePlaybackController {
    fun start(player: Player, path: List<Location>, totalDurationMs: Long)
    fun stop()
    fun isActive(): Boolean
    fun pause(): Boolean
    fun resume(): Boolean
    fun isPaused(): Boolean
    fun skip(): Boolean
}
