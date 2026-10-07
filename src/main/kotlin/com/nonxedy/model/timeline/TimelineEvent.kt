package com.nonxedy.model.timeline

data class TimelineEvent(
    val timeMs: Long,
    val type: TimelineEventType,
    val args: Map<String, String> = emptyMap()
)

enum class TimelineEventType {
    TITLE,
    SOUND,
    PARTICLE,
    CONSOLE,
    PLAYER
}
