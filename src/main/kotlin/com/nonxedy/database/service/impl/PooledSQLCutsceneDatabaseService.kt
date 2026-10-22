package com.nonxedy.database.service.impl

import com.nonxedy.database.service.PoolSettings
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection

// Base class for networked SQL databases, backed by a HikariCP connection pool
abstract class PooledSQLCutsceneDatabaseService(
    private val username: String,
    private val password: String,
    private val pool: PoolSettings
) : AbstractSQLCutsceneDatabaseService() {

    private var dataSource: HikariDataSource? = null

    // JDBC driver class, set explicitly since ServiceLoader can't see the shaded drivers
    protected abstract val driverClassName: String

    protected open fun configure(config: HikariConfig) {}

    override fun connect() {
        val config = HikariConfig().apply {
            poolName = "nonscenes-${javaClass.simpleName}"
            jdbcUrl = getJdbcUrl()
            driverClassName = this@PooledSQLCutsceneDatabaseService.driverClassName
            username = this@PooledSQLCutsceneDatabaseService.username
            password = this@PooledSQLCutsceneDatabaseService.password
            maximumPoolSize = pool.maximumPoolSize
            minimumIdle = pool.minimumIdle
            connectionTimeout = pool.connectionTimeout
            idleTimeout = pool.idleTimeout
            maxLifetime = pool.maxLifetime
        }
        configure(config)
        dataSource = HikariDataSource(config)
    }

    override fun disconnect() {
        dataSource?.close()
        dataSource = null
    }

    override fun <T> withConnection(block: (Connection) -> T): T {
        val source = dataSource ?: throw RuntimeException("Database not initialized")
        return source.connection.use(block)
    }
}
