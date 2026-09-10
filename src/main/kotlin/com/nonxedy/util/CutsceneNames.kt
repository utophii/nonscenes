package com.nonxedy.util

object CutsceneNames {
    const val MAX_LENGTH = 64
    private val ALLOWED = Regex("^[a-zA-Z0-9_-]+$")

    fun isValid(name: String): Boolean {
        return name.isNotEmpty()
            && name.length <= MAX_LENGTH
            && ALLOWED.matches(name)
    }
}
