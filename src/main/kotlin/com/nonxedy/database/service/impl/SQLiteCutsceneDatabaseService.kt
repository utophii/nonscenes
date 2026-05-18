package com.nonxedy.database.service.impl

import java.io.File

// SQLite implementation of CutsceneDatabaseService
class SQLiteCutsceneDatabaseService(private val databaseFile: File) : AbstractSQLCutsceneDatabaseService() {

    override fun getJdbcUrl(): String {
        // Ensure parent directory exists
        databaseFile.parentFile?.mkdirs()
        return "jdbc:sqlite:${databaseFile.absolutePath}"
    }

    override fun getCreateTablesSQL(): Array<String> = arrayOf(
        """
        CREATE TABLE IF NOT EXISTS cutscenes (
            name TEXT PRIMARY KEY,
            frame_count INTEGER NOT NULL,
            ticks_per_frame INTEGER NOT NULL DEFAULT 1
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
