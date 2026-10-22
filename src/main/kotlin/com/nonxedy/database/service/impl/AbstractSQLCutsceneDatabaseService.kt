package com.nonxedy.database.service.impl

import com.nonxedy.database.service.CutsceneDatabaseService
import com.nonxedy.model.Cutscene
import com.nonxedy.model.CutsceneFrame
import org.bukkit.Location
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.DriverManager
import java.sql.Types
import java.util.Properties
import java.util.logging.Level
import java.util.logging.Logger

// Abstract base class for SQL-based CutsceneDatabaseService implementations
// Provides common functionality for CRUD operations
abstract class AbstractSQLCutsceneDatabaseService : CutsceneDatabaseService {

    protected val logger: Logger = Logger.getLogger(javaClass.simpleName)
    protected var connection: Connection? = null

    // Get the JDBC URL for the database connection
    protected abstract fun getJdbcUrl(): String

    // Get the SQL for creating tables
    protected abstract fun getCreateTablesSQL(): Array<String>

    protected open fun connectionProperties(): Properties? = null

    protected open fun openConnection(): Connection {
        val props = connectionProperties()
        return if (props == null) DriverManager.getConnection(getJdbcUrl())
        else DriverManager.getConnection(getJdbcUrl(), props)
    }

    // Open the underlying connection (or pool)
    protected open fun connect() {
        connection = openConnection()
    }

    // Close the underlying connection (or pool)
    protected open fun disconnect() {
        connection?.close()
        connection = null
    }

    // Run a block with a database connection
    protected open fun <T> withConnection(block: (Connection) -> T): T =
        block(connection ?: throw RuntimeException("Database not initialized"))

    override fun initialize() {
        try {
            connect()
            createTables()
            logger.info("Database initialized successfully")
        } catch (e: Exception) {
            logger.severe("Failed to initialize database: ${e.message}")
            throw RuntimeException("Failed to initialize database", e)
        }
    }

    override fun shutdown() {
        try {
            disconnect()
            logger.info("Database connection closed")
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Error closing database connection", e)
        }
    }

    override fun saveCutscene(cutscene: Cutscene) = withConnection { conn ->
        try {
            conn.autoCommit = false

            // Delete existing cutscene
            conn.prepareStatement("DELETE FROM cutscenes WHERE name = ?").use { stmt ->
                stmt.setString(1, cutscene.name)
                stmt.executeUpdate()
            }

            conn.prepareStatement("DELETE FROM cutscene_frames WHERE cutscene_name = ?").use { stmt ->
                stmt.setString(1, cutscene.name)
                stmt.executeUpdate()
            }

            // Insert cutscene
            conn.prepareStatement("INSERT INTO cutscenes (name, frame_count, ticks_per_frame, frame_duration_ms, events_text) VALUES (?, ?, ?, ?, ?)").use { stmt ->
                stmt.setString(1, cutscene.name)
                stmt.setInt(2, cutscene.frames.size)
                stmt.setInt(3, cutscene.ticksPerFrame)
                stmt.setLong(4, cutscene.frameDurationMs)
                stmt.setString(5, com.nonxedy.model.timeline.TimelineEvents.encode(cutscene.events))
                stmt.executeUpdate()
            }

            // Insert frames
            conn.prepareStatement(
                "INSERT INTO cutscene_frames (cutscene_name, frame_index, world, x, y, z, yaw, pitch, segment_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
            ).use { stmt ->
                cutscene.frames.forEachIndexed { index, frame ->
                    val location = frame.location
                    stmt.setString(1, cutscene.name)
                    stmt.setInt(2, index)
                    stmt.setString(3, frame.worldName)
                    stmt.setDouble(4, location.x)
                    stmt.setDouble(5, location.y)
                    stmt.setDouble(6, location.z)
                    stmt.setFloat(7, location.yaw)
                    stmt.setFloat(8, location.pitch)
                    val segmentMs = frame.segmentMs
                    if (segmentMs == null) stmt.setNull(9, Types.BIGINT) else stmt.setLong(9, segmentMs)
                    stmt.executeUpdate()
                }
            }

            conn.commit()
            logger.fine("Saved cutscene: ${cutscene.name}")

        } catch (e: Exception) {
            conn.rollback()
            logger.severe("Failed to save cutscene: ${e.message}")
            throw RuntimeException("Failed to save cutscene: ${cutscene.name}", e)
        } finally {
            conn.autoCommit = true
        }
    }

    override fun loadAllCutscenes(): List<Cutscene> = withConnection { conn ->
        val cutscenes = mutableListOf<Cutscene>()

        conn.prepareStatement("""
            SELECT c.name, c.ticks_per_frame, c.frame_duration_ms, c.events_text, f.frame_index, f.world, f.x, f.y, f.z, f.yaw, f.pitch, f.segment_ms
            FROM cutscenes c
            JOIN cutscene_frames f ON c.name = f.cutscene_name
            ORDER BY c.name, f.frame_index
        """).use { stmt ->
            stmt.executeQuery().use { rs ->
                var currentCutscene: Cutscene? = null
                var currentFrames = mutableListOf<CutsceneFrame>()

                while (rs.next()) {
                    val name = rs.getString("name")
                    val ticksPerFrame = rs.getInt("ticks_per_frame").coerceAtLeast(1)
                    val frameDurationMs = rs.getLong("frame_duration_ms").takeIf { it > 0L } ?: (ticksPerFrame * 50L)
                    val events = com.nonxedy.model.timeline.TimelineEvents.decode(
                        try { rs.getString("events_text") } catch (_: Exception) { null }
                    )

                    if (currentCutscene == null || currentCutscene.name != name) {
                        if (currentCutscene != null && currentFrames.isNotEmpty()) {
                            cutscenes.add(currentCutscene)
                        }

                        currentFrames = mutableListOf()
                        currentCutscene = Cutscene(name, currentFrames, frameDurationMs, events)
                    }

                    // Add frame
                    val worldName = rs.getString("world")
                    val location = Location(
                        null,
                        rs.getDouble("x"),
                        rs.getDouble("y"),
                        rs.getDouble("z"),
                        rs.getFloat("yaw"),
                        rs.getFloat("pitch")
                    )
                    val segmentMs = rs.getLong("segment_ms").takeUnless { rs.wasNull() }
                    currentFrames.add(CutsceneFrame(location, worldName, segmentMs))
                }

                // Add last cutscene
                if (currentCutscene != null && currentFrames.isNotEmpty()) {
                    cutscenes.add(currentCutscene)
                }
            }
        }

        logger.fine("Loaded ${cutscenes.size} cutscenes from database")
        cutscenes
    }

    override fun deleteCutscene(name: String) = withConnection { conn ->
        try {
            // Delete frames first (foreign key constraint)
            conn.prepareStatement("DELETE FROM cutscene_frames WHERE cutscene_name = ?").use { stmt ->
                stmt.setString(1, name)
                stmt.executeUpdate()
            }

            // Delete cutscene
            conn.prepareStatement("DELETE FROM cutscenes WHERE name = ?").use { stmt ->
                stmt.setString(1, name)
                stmt.executeUpdate()
            }

            logger.fine("Deleted cutscene: $name")

        } catch (e: Exception) {
            logger.severe("Failed to delete cutscene: ${e.message}")
            throw RuntimeException("Failed to delete cutscene: $name", e)
        }
    }

    override fun cutsceneExists(name: String): Boolean = withConnection { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM cutscenes WHERE name = ?").use { stmt ->
            stmt.setString(1, name)
            stmt.executeQuery().use { rs ->
                val exists = rs.next() && rs.getInt(1) > 0
                logger.fine("Cutscene '$name' exists: $exists")
                exists
            }
        }
    }

    private fun createTables() = withConnection { conn ->
        try {
            getCreateTablesSQL().forEach { sql ->
                conn.createStatement().use { stmt ->
                    stmt.execute(sql)
                }
            }
            ensureTimingColumn(conn)
            logger.info("Database tables created or verified")

        } catch (e: Exception) {
            logger.severe("Failed to create database tables: ${e.message}")
            throw RuntimeException("Failed to create database tables", e)
        }
    }

    private fun ensureTimingColumn(conn: Connection) {
        val metadata = conn.metaData
        val schema = conn.schema
        if (!columnExists(metadata, schema, "cutscenes", "ticks_per_frame")) {
            conn.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE cutscenes ADD COLUMN ticks_per_frame INTEGER NOT NULL DEFAULT 1")
            }
        }

        if (!columnExists(metadata, schema, "cutscenes", "frame_duration_ms")) {
            conn.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE cutscenes ADD COLUMN frame_duration_ms BIGINT NOT NULL DEFAULT 50")
            }
        }

        if (!columnExists(metadata, schema, "cutscenes", "events_text")) {
            conn.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE cutscenes ADD COLUMN events_text TEXT")
            }
        }

        if (!columnExists(metadata, schema, "cutscene_frames", "segment_ms")) {
            conn.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE cutscene_frames ADD COLUMN segment_ms BIGINT")
            }
        }
    }

    private fun columnExists(metadata: DatabaseMetaData, schema: String?, tableName: String, columnName: String): Boolean {
        metadata.getColumns(null, schema, tableName, columnName).use { rs ->
            if (rs.next()) {
                return true
            }
        }

        metadata.getColumns(null, schema, tableName.uppercase(), columnName.uppercase()).use { rs ->
            if (rs.next()) {
                return true
            }
        }

        metadata.getColumns(null, schema, tableName.lowercase(), columnName.lowercase()).use { rs ->
            return rs.next()
        }
    }
}
