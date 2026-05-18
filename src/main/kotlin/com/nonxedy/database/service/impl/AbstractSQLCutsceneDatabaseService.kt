package com.nonxedy.database.service.impl

import com.nonxedy.database.service.CutsceneDatabaseService
import com.nonxedy.model.Cutscene
import com.nonxedy.model.CutsceneFrame
import org.bukkit.Location
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.DriverManager
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

    // Get a database connection, creating one if needed
    protected fun requireConnection(): Connection = connection ?: throw RuntimeException("Database not initialized")

    override fun initialize() {
        try {
            connection = DriverManager.getConnection(getJdbcUrl())
            createTables()
            logger.info("Database initialized successfully")
        } catch (e: Exception) {
            logger.severe("Failed to initialize database: ${e.message}")
            throw RuntimeException("Failed to initialize database", e)
        }
    }

    override fun shutdown() {
        try {
            connection?.close()
            connection = null
            logger.info("Database connection closed")
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Error closing database connection", e)
        }
    }

    override fun saveCutscene(cutscene: Cutscene) {
        val conn = connection ?: throw RuntimeException("Database not initialized")

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
            conn.prepareStatement("INSERT INTO cutscenes (name, frame_count, ticks_per_frame) VALUES (?, ?, ?)").use { stmt ->
                stmt.setString(1, cutscene.name)
                stmt.setInt(2, cutscene.frames.size)
                stmt.setInt(3, cutscene.ticksPerFrame)
                stmt.executeUpdate()
            }

            // Insert frames
            conn.prepareStatement(
                "INSERT INTO cutscene_frames (cutscene_name, frame_index, world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
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

    override fun loadAllCutscenes(): List<Cutscene> {
        val conn = connection ?: throw RuntimeException("Database not initialized")
        val cutscenes = mutableListOf<Cutscene>()

        conn.prepareStatement("""
            SELECT c.name, c.ticks_per_frame, f.frame_index, f.world, f.x, f.y, f.z, f.yaw, f.pitch
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

                    if (currentCutscene == null || currentCutscene.name != name) {
                        // Save previous cutscene
                        if (currentCutscene != null && currentFrames.isNotEmpty()) {
                            cutscenes.add(currentCutscene)
                        }

                        // Start new cutscene
                        currentFrames = mutableListOf()
                        currentCutscene = Cutscene(name, currentFrames, ticksPerFrame)
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
                    currentFrames.add(CutsceneFrame(location, worldName))
                }

                // Add last cutscene
                if (currentCutscene != null && currentFrames.isNotEmpty()) {
                    cutscenes.add(currentCutscene)
                }
            }
        }

        logger.fine("Loaded ${cutscenes.size} cutscenes from database")
        return cutscenes
    }

    override fun deleteCutscene(name: String) {
        val conn = connection ?: throw RuntimeException("Database not initialized")

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

    override fun cutsceneExists(name: String): Boolean {
        val conn = connection ?: throw RuntimeException("Database not initialized")

        conn.prepareStatement("SELECT COUNT(*) FROM cutscenes WHERE name = ?").use { stmt ->
            stmt.setString(1, name)
            stmt.executeQuery().use { rs ->
                val exists = rs.next() && rs.getInt(1) > 0
                logger.fine("Cutscene '$name' exists: $exists")
                return exists
            }
        }
    }

    private fun createTables() {
        val conn = connection ?: throw RuntimeException("Database not initialized")

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
        if (columnExists(metadata, "cutscenes", "ticks_per_frame")) {
            return
        }

        conn.createStatement().use { stmt ->
            stmt.execute("ALTER TABLE cutscenes ADD COLUMN ticks_per_frame INTEGER NOT NULL DEFAULT 1")
        }
    }

    private fun columnExists(metadata: DatabaseMetaData, tableName: String, columnName: String): Boolean {
        metadata.getColumns(null, null, tableName, columnName).use { rs ->
            if (rs.next()) {
                return true
            }
        }

        metadata.getColumns(null, null, tableName.uppercase(), columnName.uppercase()).use { rs ->
            if (rs.next()) {
                return true
            }
        }

        metadata.getColumns(null, null, tableName.lowercase(), columnName.lowercase()).use { rs ->
            return rs.next()
        }
    }
}
