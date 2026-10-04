package com.nonxedy.listener

object PlaybackCommandPolicy {
    fun isAllowedDuringPlayback(raw: String): Boolean {
        val command = raw.trim().lowercase().removePrefix("/")
        return command == "nonscene stop"
            || command == "ns stop"
            || command.startsWith("nonscene stop ")
            || command.startsWith("ns stop ")
    }
}
