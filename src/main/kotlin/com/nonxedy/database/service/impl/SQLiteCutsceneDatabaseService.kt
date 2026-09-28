package com.nonxedy.database.service.impl

import com.nonxedy.model.Cutscene
import org.sqlite.SQLiteConfig
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

class SQLiteCutsceneDatabaseService(private val databaseFile: File) : AbstractSQLCutsceneDatabaseService() {

    private val lock = Any()

    override fun getJdbcUrl(): String {
        databaseFile.parentFile?.mkdirs()
        return "jdbc:sqlite:${databaseFile.absolutePath}"
    }

    override fun openConnection(): Connection {
        databaseFile.parentFile?.mkdirs()
        val config = SQLiteConfig()
        config.setJournalMode(SQLiteConfig.JournalMode.WAL)
        config.setBusyTimeout(5_000)
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
        config.enforceForeignKeys(true)
        return DriverManager.getConnection(getJdbcUrl(), config.toProperties())
    }

    override fun initialize() = synchronized(lock) { super.initialize() }

    override fun shutdown() = synchronized(lock) { super.shutdown() }

    override fun saveCutscene(cutscene: Cutscene) = synchronized(lock) { super.saveCutscene(cutscene) }

    override fun loadAllCutscenes(): List<Cutscene> = synchronized(lock) { super.loadAllCutscenes() }

    override fun deleteCutscene(name: String) = synchronized(lock) { super.deleteCutscene(name) }

    override fun cutsceneExists(name: String): Boolean = synchronized(lock) { super.cutsceneExists(name) }

    override fun getCreateTablesSQL(): Array<String> = arrayOf(
        """
        CREATE TABLE IF NOT EXISTS cutscenes (
            name TEXT PRIMARY KEY,
            frame_count INTEGER NOT NULL,
            ticks_per_frame INTEGER NOT NULL DEFAULT 1,
            frame_duration_ms INTEGER NOT NULL DEFAULT 50
        )
        """.trimIndent(),

        """
        CREATE TABLE IF NOT EXISTS cutscene_frames (
            cutscene_name TEXT NOT NULL,
            frame_index INTEGER NOT NULL,
            world TEXT NOT NULL,
            x REAL NOT NULL,
            y REAL NOT NULL,
            z REAL NOT NULL,
            yaw REAL NOT NULL,
            pitch REAL NOT NULL,
            PRIMARY KEY (cutscene_name, frame_index),
            FOREIGN KEY (cutscene_name) REFERENCES cutscenes(name) ON DELETE CASCADE
        )
        """.trimIndent()
    )
}