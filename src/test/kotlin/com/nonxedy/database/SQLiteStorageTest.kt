package com.nonxedy.database

import com.nonxedy.database.service.impl.SQLiteCutsceneDatabaseService
import com.nonxedy.model.Cutscene
import com.nonxedy.model.CutsceneFrame
import com.nonxedy.model.timeline.TimelineEvent
import com.nonxedy.model.timeline.TimelineEventType
import org.bukkit.Location
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager

class SQLiteStorageTest {

    @TempDir
    lateinit var dir: File

    private val opened = mutableListOf<SQLiteCutsceneDatabaseService>()

    private fun open(): SQLiteCutsceneDatabaseService =
        SQLiteCutsceneDatabaseService(File(dir, "cutscenes.db")).also {
            it.initialize()
            opened.add(it)
        }

    @AfterEach
    fun close() = opened.forEach { it.shutdown() }

    private fun frame(x: Double, world: String = "world", segmentMs: Long? = null) =
        CutsceneFrame(Location(null, x, 64.5, -3.25, 90f, -15f), world, segmentMs)

    private val cutscene = Cutscene(
        "Intro_01",
        listOf(frame(0.0, segmentMs = 1500), frame(10.0, "world_nether", 750), frame(20.0)),
        frameDurationMs = 100,
        events = listOf(
            TimelineEvent(500, TimelineEventType.TITLE, mapOf("title" to "Hello", "subtitle" to "there")),
            TimelineEvent(1200, TimelineEventType.CONSOLE, mapOf("command" to "say {player} a=b"))
        )
    )

    private fun assertSameCutscene(expected: Cutscene, actual: Cutscene) {
        assertEquals(expected.name, actual.name)
        assertEquals(expected.frameDurationMs, actual.frameDurationMs)
        assertEquals(expected.events, actual.events)
        assertEquals(expected.frames.size, actual.frames.size)
        expected.frames.zip(actual.frames).forEach { (e, a) ->
            assertEquals(e.worldName, a.worldName)
            assertEquals(e.segmentMs, a.segmentMs)
            assertEquals(e.location.x, a.location.x, 1e-9)
            assertEquals(e.location.y, a.location.y, 1e-9)
            assertEquals(e.location.z, a.location.z, 1e-9)
            assertEquals(e.location.yaw, a.location.yaw, 1e-4f)
            assertEquals(e.location.pitch, a.location.pitch, 1e-4f)
        }
    }

    @Test
    fun `saved cutscene loads back unchanged`() {
        val db = open()
        db.saveCutscene(cutscene)
        assertSameCutscene(cutscene, db.loadAllCutscenes().single())
    }

    @Test
    fun `cutscenes survive closing and reopening the database`() {
        open().apply { saveCutscene(cutscene); shutdown() }
        assertSameCutscene(cutscene, open().loadAllCutscenes().single())
    }

    @Test
    fun `saving again replaces the old frames and events`() {
        val db = open()
        db.saveCutscene(cutscene)
        val shorter = cutscene.copy(frames = cutscene.frames.take(2), events = emptyList())
        db.saveCutscene(shorter)
        assertSameCutscene(shorter, db.loadAllCutscenes().single())
    }

    @Test
    fun `delete removes the cutscene and its frames`() {
        val db = open()
        db.saveCutscene(cutscene)
        db.saveCutscene(cutscene.copy(name = "other"))
        assertTrue(db.cutsceneExists("Intro_01"))

        db.deleteCutscene("Intro_01")

        assertFalse(db.cutsceneExists("Intro_01"))
        assertEquals(listOf("other"), db.loadAllCutscenes().map { it.name })
        DriverManager.getConnection("jdbc:sqlite:${File(dir, "cutscenes.db").absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM cutscene_frames WHERE cutscene_name = 'Intro_01'").use {
                    it.next()
                    assertEquals(0, it.getInt(1))
                }
            }
        }
    }

    @Test
    fun `old database gets the timing and event columns on startup`() {
        DriverManager.getConnection("jdbc:sqlite:${File(dir, "cutscenes.db").absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE cutscenes (name TEXT PRIMARY KEY, frame_count INTEGER NOT NULL)")
                stmt.execute(
                    "CREATE TABLE cutscene_frames (cutscene_name TEXT NOT NULL, frame_index INTEGER NOT NULL, " +
                        "world TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, " +
                        "yaw REAL NOT NULL, pitch REAL NOT NULL, PRIMARY KEY (cutscene_name, frame_index))"
                )
                stmt.execute("INSERT INTO cutscenes VALUES ('legacy', 2)")
                stmt.execute("INSERT INTO cutscene_frames VALUES ('legacy', 0, 'world', 0, 64, 0, 0, 0)")
                stmt.execute("INSERT INTO cutscene_frames VALUES ('legacy', 1, 'world', 5, 64, 0, 0, 0)")
            }
        }

        val db = open()
        val legacy = db.loadAllCutscenes().single()
        assertEquals("legacy", legacy.name)
        assertEquals(50L, legacy.frameDurationMs)
        assertTrue(legacy.isRawRecording)
        assertTrue(legacy.events.isEmpty())

        db.saveCutscene(cutscene)
        assertSameCutscene(cutscene, db.loadAllCutscenes().first { it.name == cutscene.name })
    }
}
