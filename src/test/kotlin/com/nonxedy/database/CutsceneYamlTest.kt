package com.nonxedy.database

import com.nonxedy.model.Cutscene
import com.nonxedy.model.CutsceneFrame
import com.nonxedy.model.timeline.TimelineEvent
import com.nonxedy.model.timeline.TimelineEventType
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import java.io.File

class CutsceneYamlTest {

    @TempDir
    lateinit var dir: File

    private val world: World = Mockito.mock(World::class.java).also {
        Mockito.`when`(it.name).thenReturn("world")
    }

    private val worlds: (String) -> World? = { if (it == "world") world else null }

    private val cutscene = Cutscene(
        "intro",
        listOf(
            CutsceneFrame(Location(world, 1.5, 64.0, -2.0, 45f, 10f), "world", 2000),
            CutsceneFrame(Location(null, 30.0, 40.0, 50.0, -90f, 0f), "world_nether")
        ),
        frameDurationMs = 150,
        events = listOf(TimelineEvent(250, TimelineEventType.SOUND, mapOf("sound" to "ui.button.click", "volume" to "1")))
    )

    @Test
    fun `file round trip keeps frames, timing and events`() {
        val file = File(dir, "intro.yml")
        CutsceneYaml.write(cutscene, file)
        val read = CutsceneYaml.read(file, "intro", worlds)

        assertEquals(cutscene.frameDurationMs, read.frameDurationMs)
        assertEquals(cutscene.events, read.events)
        assertEquals(listOf(2000L, null), read.frames.map { it.segmentMs })
        assertEquals(cutscene.frames.map { it.location }, read.frames.map { it.location })
    }

    @Test
    fun `frames in an unloaded world keep their world name`() {
        val read = CutsceneYaml.fromYaml(CutsceneYaml.toYaml(cutscene), "intro", worlds)
        val nether = read.frames[1]
        assertNull(nether.location.world)
        assertEquals("world_nether", nether.worldName)
    }

    @Test
    fun `frames are read in index order, not key order`() {
        val frames = (0..11).map { CutsceneFrame(Location(world, it.toDouble(), 64.0, 0.0), "world") }
        val read = CutsceneYaml.fromYaml(CutsceneYaml.toYaml(cutscene.copy(frames = frames)), "intro", worlds)
        assertEquals((0..11).map { it.toDouble() }, read.frames.map { it.location.x })
    }

    @Test
    fun `legacy files without frame-duration-ms use ticks-per-frame`() {
        val yaml = YamlConfiguration()
        yaml.set("ticks-per-frame", 3)
        yaml.set("frames.0.world", "world")
        val read = CutsceneYaml.fromYaml(yaml, "old", worlds)
        assertEquals(150L, read.frameDurationMs)
        assertEquals(1, read.frames.size)
    }
}
