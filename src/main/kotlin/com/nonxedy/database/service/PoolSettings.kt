package com.nonxedy.database.service

import org.bukkit.configuration.ConfigurationSection

// Connection pool settings for networked SQL databases
data class PoolSettings(
    val maximumPoolSize: Int = 10,
    val minimumIdle: Int = 2,
    val connectionTimeout: Long = 30_000,
    val idleTimeout: Long = 600_000,
    val maxLifetime: Long = 1_800_000
) {
    companion object {
        fun from(section: ConfigurationSection?): PoolSettings {
            val defaults = PoolSettings()
            if (section == null) return defaults
            return PoolSettings(
                maximumPoolSize = section.getInt("maximum-pool-size", defaults.maximumPoolSize),
                minimumIdle = section.getInt("minimum-idle", defaults.minimumIdle),
                connectionTimeout = section.getLong("connection-timeout", defaults.connectionTimeout),
                idleTimeout = section.getLong("idle-timeout", defaults.idleTimeout),
                maxLifetime = section.getLong("max-lifetime", defaults.maxLifetime)
            )
        }
    }
}
