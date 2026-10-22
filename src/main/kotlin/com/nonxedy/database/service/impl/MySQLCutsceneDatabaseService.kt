package com.nonxedy.database.service.impl

import com.nonxedy.database.service.PoolSettings

class MySQLCutsceneDatabaseService(
    private val host: String,
    private val port: Int,
    private val database: String,
    username: String,
    password: String,
    pool: PoolSettings
) : PooledSQLCutsceneDatabaseService(username, password, pool) {

    override val driverClassName = "com.mysql.cj.jdbc.Driver"

    override fun getJdbcUrl(): String {
        return "jdbc:mysql://$host:$port/$database?serverTimezone=UTC"
    }

    override fun getCreateTablesSQL(): Array<String> = arrayOf(
        """
        CREATE TABLE IF NOT EXISTS cutscenes (
            name VARCHAR(255) PRIMARY KEY,
            frame_count INT NOT NULL,
            ticks_per_frame INT NOT NULL DEFAULT 1,
            frame_duration_ms BIGINT NOT NULL DEFAULT 50,
            events_text LONGTEXT
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """.trimIndent(),

        """
        CREATE TABLE IF NOT EXISTS cutscene_frames (
            cutscene_name VARCHAR(255) NOT NULL,
            frame_index INT NOT NULL,
            world VARCHAR(255) NOT NULL,
            x DOUBLE NOT NULL,
            y DOUBLE NOT NULL,
            z DOUBLE NOT NULL,
            yaw FLOAT NOT NULL,
            pitch FLOAT NOT NULL,
            segment_ms BIGINT,
            PRIMARY KEY (cutscene_name, frame_index),
            FOREIGN KEY (cutscene_name) REFERENCES cutscenes(name) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """.trimIndent()
    )
}
