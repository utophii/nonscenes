package com.nonxedy.playback

import com.nonxedy.model.timeline.TimelineEvent
import com.nonxedy.model.timeline.TimelineEvents
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.ArrayDeque

class TimelineClock(
    private val plugin: JavaPlugin,
    private val player: Player,
    private val cutsceneName: String,
    events: List<TimelineEvent>
) {
    private val queue = ArrayDeque(events.sortedBy { it.timeMs })

    fun pulse(elapsedMs: Long) {
        while (queue.isNotEmpty() && queue.first().timeMs <= elapsedMs) {
            val event = queue.removeFirst()
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (player.isOnline) TimelineEvents.fire(player, cutsceneName, event)
            })
        }
    }
}
