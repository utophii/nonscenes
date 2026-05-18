package com.nonxedy.database.service.impl

// PostgreSQL implementation of CutsceneDatabaseService
class PostgreSQLCutsceneDatabaseService(
    private val host: String,
    private val port: Int,
    private val database: String,
    private val username: String,
    private val password: String
) : AbstractSQLCutsceneDatabaseService() {

    override fun getJdbcUrl(): String {
        return "jdbc:postgresql://$host:$port/$database?user=$username&password=$password"
    }

    override fun getCreateTablesSQL(): Array<String> = arrayOf(
        """
        CREATE TABLE IF NOT EXISTS cutscenes (
            name VARCHAR(255) PRIMARY KEY,
            frame_count INTEGER NOT NULL,
            ticks_per_frame INTEGER NOT NULL DEFAULT 1
        )
        """.trimIndent(),

        """
        CREATE TABLE IF NOT EXISTS cutscene_frames (
            cutscene_name VARCHAR(255) NOT NULL,
            frame_index INTEGER NOT NULL,
            world VARCHAR(255) NOT NULL,
            x DOUBLE PRECISION NOT NULL,
            y DOUBLE PRECISION NOT NULL,
            z DOUBLE PRECISION NOT NULL,
            yaw REAL NOT NULL,
            pitch REAL NOT NULL,
            PRIMARY KEY (cutscene_name, frame_index),
            FOREIGN KEY (cutscene_name) REFERENCES cutscenes(name) ON DELETE CASCADE
        )
        """.trimIndent()
    )
}
