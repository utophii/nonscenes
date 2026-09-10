package com.nonxedy.util

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CutsceneNamesTest {

    @Test
    fun `accepts letters digits underscore and hyphen`() {
        assertTrue(CutsceneNames.isValid("intro"))
        assertTrue(CutsceneNames.isValid("Intro_01"))
        assertTrue(CutsceneNames.isValid("a-b_9"))
    }

    @Test
    fun `rejects empty and too long names`() {
        assertFalse(CutsceneNames.isValid(""))
        assertFalse(CutsceneNames.isValid("a".repeat(CutsceneNames.MAX_LENGTH + 1)))
    }

    @Test
    fun `rejects path traversal and separators`() {
        assertFalse(CutsceneNames.isValid("../oops"))
        assertFalse(CutsceneNames.isValid("..\\oops"))
        assertFalse(CutsceneNames.isValid("foo/bar"))
        assertFalse(CutsceneNames.isValid("foo\\bar"))
        assertFalse(CutsceneNames.isValid("foo.bar"))
        assertFalse(CutsceneNames.isValid("foo bar"))
        assertFalse(CutsceneNames.isValid("."))
        assertFalse(CutsceneNames.isValid(".."))
    }
}
