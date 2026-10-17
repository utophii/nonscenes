package com.nonxedy.listener

object PlaybackCommandPolicy {
    private val ROOTS = setOf("nonscene", "ns")
    private val ALLOWED = setOf("stop", "skip", "pause", "resume")

    fun isAllowedDuringPlayback(raw: String): Boolean {
        val parts = raw.trim().lowercase().removePrefix("/").split(' ').filter { it.isNotEmpty() }
        return parts.size >= 2 && parts[0] in ROOTS && parts[1] in ALLOWED
    }
}
