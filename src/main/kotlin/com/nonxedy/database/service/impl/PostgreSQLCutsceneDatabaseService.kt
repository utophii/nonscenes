package com.nonxedy.database.service.impl

import com.nonxedy.database.service.PoolSettings
import com.zaxxer.hikari.HikariConfig

class PostgreSQLCutsceneDatabaseService(
    private val host: String,
    private val port: Int,
    private val database: String,
    private val schema: String,
    username: String,
    password: String,
    pool: PoolSettings
) : PooledSQLCutsceneDatabaseService(username, password, pool) {

    override val driverClassName = "org.postgresql.Driver"

    override fun getJdbcUrl(): String {
        return "jdbc:postgresql://$host:$port/$database"
    }

    override fun configure(config: HikariConfig) {
        config.schema = schema
    }

    override fun getCreateTablesSQL(): Array<String> = arrayOf(
        """
        CREATE TABLE IF NOT EXISTS cutscenes (
            name VARCHAR(255) PRIMARY KEY,
            frame_count INTEGER NOT NULL,
            ticks_per_frame INTEGER NOT NULL DEFAULT 1,
            frame_duration_ms BIGINT NOT NULL DEFAULT 50,
            events_text TEXT
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
            segment_ms BIGINT,
            PRIMARY KEY (cutscene_name, frame_index),
            FOREIGN KEY (cutscene_name) REFERENCES cutscenes(name) ON DELETE CASCADE
        )
        """.trimIndent()
    )
}
