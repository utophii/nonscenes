package com.nonxedy.database.service.impl

// MySQL implementation of CutsceneDatabaseService
class MySQLCutsceneDatabaseService(
    private val host: String,
    private val port: Int,
    private val database: String,
    private val username: String,
    private val password: String
) : AbstractSQLCutsceneDatabaseService() {

    override fun getJdbcUrl(): String {
        return "jdbc:mysql://$host:$port/$database?useSSL=false&serverTimezone=UTC&user=$username&password=$password"
    }

    override fun getCreateTablesSQL(): Array<String> = arrayOf(
        """
        CREATE TABLE IF NOT EXISTS cutscenes (
            name VARCHAR(255) PRIMARY KEY,
            frame_count INT NOT NULL,
            ticks_per_frame INT NOT NULL DEFAULT 1
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
            PRIMARY KEY (cutscene_name, frame_index),
            FOREIGN KEY (cutscene_name) REFERENCES cutscenes(name) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """.trimIndent()
    )
}
