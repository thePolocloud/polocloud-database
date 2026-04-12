package de.polocloud.database.nosql.redis

import de.polocloud.database.DatabaseConnectionFactory
import de.polocloud.database.DatabaseCredentials
import de.polocloud.database.DatabaseState
import de.polocloud.i18n.api.trError
import de.polocloud.i18n.api.trInfo
import de.polocloud.i18n.api.trWarn
import redis.clients.jedis.RedisClient
import redis.clients.jedis.UnifiedJedis

class RedisConnectionFactory(credentials: DatabaseCredentials.Redis) : DatabaseConnectionFactory<DatabaseCredentials.Redis>(credentials) {

    private lateinit var jedis: UnifiedJedis
    private lateinit var executor: RedisExecutor

    override fun connect(credentials: DatabaseCredentials.Redis) {

        state = DatabaseState.CONNECTING

        val uri = if (!credentials.password.isNullOrBlank()) {
            "redis://:${credentials.password}@" + credentials.address()
        } else {
            "redis://${credentials.address()}"
        }

        jedis = RedisClient.create(uri)

        executor = RedisExecutor(jedis)

        state = DatabaseState.CONNECTED
    }

    override fun executor() = executor

    override fun close() {
        if (state == DatabaseState.CLOSED) {
            logger.trWarn("database", "database.connection.already_closed")
            return
        }

        try {
            jedis.close()
            state = DatabaseState.CLOSED
            logger.trInfo("database","database.connection.closed.with_mode")
        } catch (e: Exception) {
            logger.trError("database","database.connection.close.failed", e)
        }
    }
}
