package com.nonxedy.database.service.impl

import com.nonxedy.database.service.CutsceneDatabaseService
import com.nonxedy.model.Cutscene
import com.nonxedy.model.CutsceneFrame
import org.bukkit.Bukkit
import org.bukkit.Location
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import java.util.logging.Level

// Redis implementation of CutsceneDatabaseService
// TODO: Fix redis is in-memory, data will be lost on server restart
class RedisCutsceneDatabaseService(
    private val host: String = "localhost",
    private val port: Int = 6379,
    private val password: String? = null,
    private val database: Int = 0
) : CutsceneDatabaseService {

    private var jedisPool: JedisPool? = null
    private val logger = java.util.logging.Logger.getLogger("nonscenes-db-redis")

    override fun initialize() {
        try {
            val poolConfig = JedisPoolConfig()
            poolConfig.maxTotal = 10
            poolConfig.maxIdle = 5
            poolConfig.minIdle = 1

            jedisPool = if (password != null) {
                JedisPool(poolConfig, host, port, 2000, password, database)
            } else {
                JedisPool(poolConfig, host, port, 2000, null, database)
            }

            logger.info("Redis database initialized: $host:$port (db: $database)")
        } catch (e: Exception) {
            logger.severe("Failed to initialize Redis database: ${e.message}")
            throw RuntimeException("Failed to initialize Redis database", e)
        }
    }

    override fun shutdown() {
        try {
            jedisPool?.close()
            jedisPool = null
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Error closing Redis connection", e)
        }
    }

    override fun saveCutscene(cutscene: Cutscene) {
        val pool = jedisPool ?: throw RuntimeException("Database not initialized")

        pool.resource.use { jedis ->
            try {
                val key = "cutscene:${cutscene.name}"

                // Delete existing cutscene
                jedis.del(key)

                // Store cutscene metadata
                jedis.hset(key, "name", cutscene.name)
                jedis.hset(key, "frameCount", cutscene.frames.size.toString())
                jedis.hset(key, "ticksPerFrame", cutscene.ticksPerFrame.toString())

                // Store frames
                cutscene.frames.forEachIndexed { index, frame ->
                    val location = frame.location
                    val frameKey = "$key:frame:$index"
                    jedis.hset(frameKey, "world", location.world?.name ?: "world")
                    jedis.hset(frameKey, "x", location.x.toString())
                    jedis.hset(frameKey, "y", location.y.toString())
                    jedis.hset(frameKey, "z", location.z.toString())
                    jedis.hset(frameKey, "yaw", location.yaw.toString())
                    jedis.hset(frameKey, "pitch", location.pitch.toString())
                }

            } catch (e: Exception) {
                logger.severe("Failed to save cutscene: ${e.message}")
                throw RuntimeException("Failed to save cutscene: ${cutscene.name}", e)
            }
        }
    }

    override fun loadAllCutscenes(): List<Cutscene> {
        val pool = jedisPool ?: throw RuntimeException("Database not initialized")
        val cutscenes = mutableListOf<Cutscene>()

        pool.resource.use { jedis ->
            try {
                // Get all cutscene keys
                val keys = jedis.keys("cutscene:*").filter { !it.contains(":frame:") }

                for (key in keys) {
                    val name = jedis.hget(key, "name")
                    val frameCountStr = jedis.hget(key, "frameCount")
                    val ticksPerFrame = (jedis.hget(key, "ticksPerFrame")?.toIntOrNull() ?: 1).coerceAtLeast(1)

                    if (name != null && frameCountStr != null) {
                        val frameCount = frameCountStr.toIntOrNull() ?: 0
                        val frames = mutableListOf<CutsceneFrame>()

                        // Load frames
                        for (i in 0 until frameCount) {
                            val frameKey = "$key:frame:$i"
                            val worldName = jedis.hget(frameKey, "world")
                            val xStr = jedis.hget(frameKey, "x")
                            val yStr = jedis.hget(frameKey, "y")
                            val zStr = jedis.hget(frameKey, "z")
                            val yawStr = jedis.hget(frameKey, "yaw")
                            val pitchStr = jedis.hget(frameKey, "pitch")

                            if (worldName != null && xStr != null && yStr != null && zStr != null &&
                                yawStr != null && pitchStr != null) {

                                val world = Bukkit.getWorld(worldName)
                                if (world != null) {
                                    try {
                                        val location = Location(
                                            world,
                                            xStr.toDouble(),
                                            yStr.toDouble(),
                                            zStr.toDouble(),
                                            yawStr.toFloat(),
                                            pitchStr.toFloat()
                                        )
                                        frames.add(CutsceneFrame(location))
                                    } catch (e: NumberFormatException) {
                                        logger.warning("Invalid frame data for cutscene $name, frame $i")
                                    }
                                }
                            }
                        }

                        if (frames.isNotEmpty()) {
                            cutscenes.add(Cutscene(name, frames, ticksPerFrame))
                        }
                    }
                }

            } catch (e: Exception) {
                logger.severe("Failed to load cutscenes: ${e.message}")
                throw RuntimeException("Failed to load cutscenes", e)
            }
        }

        return cutscenes
    }

    override fun deleteCutscene(name: String) {
        val pool = jedisPool ?: throw RuntimeException("Database not initialized")

        pool.resource.use { jedis ->
            try {
                val key = "cutscene:$name"

                // Get frame count to delete all frames
                val frameCountStr = jedis.hget(key, "frameCount")
                if (frameCountStr != null) {
                    val frameCount = frameCountStr.toIntOrNull() ?: 0
                    for (i in 0 until frameCount) {
                        jedis.del("$key:frame:$i")
                    }
                }

                // Delete main cutscene key
                jedis.del(key)

            } catch (e: Exception) {
                logger.severe("Failed to delete cutscene: ${e.message}")
                throw RuntimeException("Failed to delete cutscene: $name", e)
            }
        }
    }

    override fun cutsceneExists(name: String): Boolean {
        val pool = jedisPool ?: throw RuntimeException("Database not initialized")

        pool.resource.use { jedis ->
            try {
                return jedis.exists("cutscene:$name")

            } catch (e: Exception) {
                logger.severe("Failed to check if cutscene exists: ${e.message}")
                throw RuntimeException("Failed to check if cutscene exists: $name", e)
            }
        }
    }
}
