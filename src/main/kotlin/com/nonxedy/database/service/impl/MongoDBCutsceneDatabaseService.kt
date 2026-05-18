package com.nonxedy.database.service.impl

import com.nonxedy.database.service.CutsceneDatabaseService
import com.nonxedy.model.Cutscene
import com.nonxedy.model.CutsceneFrame
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import org.bson.Document
import org.bukkit.Bukkit
import org.bukkit.Location
import java.util.logging.Level

// MongoDB implementation of CutsceneDatabaseService
class MongoDBCutsceneDatabaseService(
    private val connectionString: String,
    private val databaseName: String
) : CutsceneDatabaseService {

    private var mongoClient: MongoClient? = null
    private var database: MongoDatabase? = null
    private val logger = java.util.logging.Logger.getLogger("nonscenes-db-mongo")

    override fun initialize() {
        try {
            mongoClient = MongoClients.create(connectionString)
            database = mongoClient?.getDatabase(databaseName)

            // Create collections and indexes
            createCollections()

            logger.info("MongoDB database initialized: $databaseName")
        } catch (e: Exception) {
            logger.severe("Failed to initialize MongoDB database: ${e.message}")
            throw RuntimeException("Failed to initialize MongoDB database", e)
        }
    }

    override fun shutdown() {
        try {
            mongoClient?.close()
            mongoClient = null
            database = null
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Error closing MongoDB connection", e)
        }
    }

    override fun saveCutscene(cutscene: Cutscene) {
        val db = database ?: throw RuntimeException("Database not initialized")

        try {
            val collection = db.getCollection("cutscenes")

            // Delete existing cutscene
            collection.deleteOne(Document("name", cutscene.name))

            // Create document
            val cutsceneDoc = Document()
                .append("name", cutscene.name)
                .append("frameCount", cutscene.frames.size)
                .append("ticksPerFrame", cutscene.ticksPerFrame)

            // Add frames
            val framesArray = mutableListOf<Document>()
            cutscene.frames.forEachIndexed { index, frame ->
                val location = frame.location
                val frameDoc = Document()
                    .append("frameIndex", index)
                    .append("world", location.world?.name ?: "world")
                    .append("x", location.x)
                    .append("y", location.y)
                    .append("z", location.z)
                    .append("yaw", location.yaw)
                    .append("pitch", location.pitch)
                framesArray.add(frameDoc)
            }

            cutsceneDoc.append("frames", framesArray)
            collection.insertOne(cutsceneDoc)

        } catch (e: Exception) {
            logger.severe("Failed to save cutscene: ${e.message}")
            throw RuntimeException("Failed to save cutscene: ${cutscene.name}", e)
        }
    }

    override fun loadAllCutscenes(): List<Cutscene> {
        val db = database ?: throw RuntimeException("Database not initialized")
        val cutscenes = mutableListOf<Cutscene>()

        try {
            val collection = db.getCollection("cutscenes")
            val documents = collection.find()

            for (doc in documents) {
                val name = doc.getString("name")
                val framesArray = doc.getList("frames", Document::class.java)
                val ticksPerFrame = doc.getInteger("ticksPerFrame", 1).coerceAtLeast(1)

                val frames = mutableListOf<CutsceneFrame>()
                for (frameDoc in framesArray) {
                    val worldName = frameDoc.getString("world")
                    val world = Bukkit.getWorld(worldName)
                    if (world != null) {
                        val location = Location(
                            world,
                            frameDoc.getDouble("x"),
                            frameDoc.getDouble("y"),
                            frameDoc.getDouble("z"),
                            frameDoc.getDouble("yaw").toFloat(),
                            frameDoc.getDouble("pitch").toFloat()
                        )
                        frames.add(CutsceneFrame(location))
                    }
                }

                if (frames.isNotEmpty()) {
                    cutscenes.add(Cutscene(name, frames, ticksPerFrame))
                }
            }

        } catch (e: Exception) {
            logger.severe("Failed to load cutscenes: ${e.message}")
            throw RuntimeException("Failed to load cutscenes", e)
        }

        return cutscenes
    }

    override fun deleteCutscene(name: String) {
        val db = database ?: throw RuntimeException("Database not initialized")

        try {
            val collection = db.getCollection("cutscenes")
            collection.deleteOne(Document("name", name))

        } catch (e: Exception) {
            logger.severe("Failed to delete cutscene: ${e.message}")
            throw RuntimeException("Failed to delete cutscene: $name", e)
        }
    }

    override fun cutsceneExists(name: String): Boolean {
        val db = database ?: throw RuntimeException("Database not initialized")

        try {
            val collection = db.getCollection("cutscenes")
            val count = collection.countDocuments(Document("name", name))
            return count > 0

        } catch (e: Exception) {
            logger.severe("Failed to check if cutscene exists: ${e.message}")
            throw RuntimeException("Failed to check if cutscene exists: $name", e)
        }
    }

    private fun createCollections() {
        val db = database ?: throw RuntimeException("Database not initialized")

        try {
            // Create collections (MongoDB creates them automatically when first used)
            val collection = db.getCollection("cutscenes")

            // Create indexes
            collection.createIndex(Document("name", 1))

        } catch (e: Exception) {
            logger.severe("Failed to create MongoDB collections: ${e.message}")
            throw RuntimeException("Failed to create database collections", e)
        }
    }
}
