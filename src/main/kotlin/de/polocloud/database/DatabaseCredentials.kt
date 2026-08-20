package de.polocloud.database

import de.polocloud.database.nosql.mongo.MongoConnectionFactory
import de.polocloud.database.nosql.redis.RedisConnectionFactory
import de.polocloud.database.sql.SqlConnectionFactory
import kotlinx.serialization.Serializable

@Serializable
sealed class DatabaseCredentials {

    abstract val hostname: String
    abstract val port: Int

    abstract fun factory(): DatabaseConnectionFactory<*>

    fun address() = "$hostname:$port"

    @Serializable
    sealed class DatabaseRelated : DatabaseCredentials() {
        abstract val username: String
        abstract val password: String
        abstract val database: String
    }

    @Serializable
    data class MariaDB(
        override val hostname: String,
        override val port: Int,
        override val username: String,
        override val password: String,
        override val database: String
    ) : DatabaseRelated() {
        override fun factory(): DatabaseConnectionFactory<*> = SqlConnectionFactory(this)
    }

    @Serializable
    data class MongoDB(
        override val hostname: String,
        override val port: Int,
        override val username: String,
        override val password: String,
        override val database: String
    ) : DatabaseRelated() {
        override fun factory(): DatabaseConnectionFactory<*> = MongoConnectionFactory(this)
    }

    @Serializable
    data class Redis(
        override val hostname: String,
        override val port: Int,
        val username: String,
        val password: String?
    ) : DatabaseCredentials() {
        override fun factory(): DatabaseConnectionFactory<*> = RedisConnectionFactory(this)
    }

    @Serializable
    data class PostgreSQL(
        override val hostname: String,
        override val port: Int,
        override val username: String,
        override val password: String,
        override val database: String
    ) : DatabaseRelated() {
        override fun factory(): DatabaseConnectionFactory<*> = SqlConnectionFactory(this)
    }

    @Serializable
    data class H2(
        val path: String
    ) : DatabaseCredentials() {
        override val hostname: String get() = "localhost"
        override val port: Int get() = 0

        override fun factory(): DatabaseConnectionFactory<*> = SqlConnectionFactory(this)
    }

    @Serializable
    data class Mysql(
        override val hostname: String,
        override val port: Int,
        override val username: String,
        override val password: String,
        override val database: String
    ) : DatabaseRelated() {
        override fun factory(): DatabaseConnectionFactory<*> = SqlConnectionFactory(this)
    }

    /**
     * Wire-compatible with PostgreSQL - [de.polocloud.database.sql.SqlConnectionFactory] routes
     * this through the same `postgresql` JDBC driver, just under its own credential type for
     * clarity in configs and logs.
     */
    @Serializable
    data class CockroachDB(
        override val hostname: String,
        override val port: Int,
        override val username: String,
        override val password: String,
        override val database: String
    ) : DatabaseRelated() {
        override fun factory(): DatabaseConnectionFactory<*> = SqlConnectionFactory(this)
    }

    @Serializable
    data class SqlServer(
        override val hostname: String,
        override val port: Int,
        override val username: String,
        override val password: String,
        override val database: String
    ) : DatabaseRelated() {
        override fun factory(): DatabaseConnectionFactory<*> = SqlConnectionFactory(this)
    }

    /**
     * [database] is used as the Oracle *service name* (modern "easy connect" syntax,
     * `@//host:port/service`), not a legacy SID.
     */
    @Serializable
    data class Oracle(
        override val hostname: String,
        override val port: Int,
        override val username: String,
        override val password: String,
        override val database: String
    ) : DatabaseRelated() {
        override fun factory(): DatabaseConnectionFactory<*> = SqlConnectionFactory(this)
    }

    /**
     * Embedded, file-based database - same shape as [H2], but SQLite takes [path] as the literal
     * file name (no extension is appended automatically, unlike H2's `.mv.db`) and has no
     * built-in authentication, so any configured username/password is ignored.
     */
    @Serializable
    data class SQLite(
        val path: String
    ) : DatabaseCredentials() {
        override val hostname: String get() = "localhost"
        override val port: Int get() = 0

        override fun factory(): DatabaseConnectionFactory<*> = SqlConnectionFactory(this)
    }
}