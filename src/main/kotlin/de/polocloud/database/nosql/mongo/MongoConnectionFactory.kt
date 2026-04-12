package de.polocloud.database.nosql.mongo

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import de.polocloud.database.DatabaseConnectionFactory
import de.polocloud.database.DatabaseCredentials
import de.polocloud.database.DatabaseState
import de.polocloud.i18n.api.TranslationService
import de.polocloud.i18n.api.trError
import de.polocloud.i18n.api.trInfo
import de.polocloud.i18n.api.trWarn
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

class MongoConnectionFactory(credentials: DatabaseCredentials.MongoDB) : DatabaseConnectionFactory<DatabaseCredentials.MongoDB>(credentials) {

    companion object {
        private val logger: Logger = LogManager.getLogger(MongoConnectionFactory::class.java)
    }

    private var executor : MongoExecutor? = null;

    var client: MongoClient? = null

    override fun connect(credentials: DatabaseCredentials.MongoDB) {
        state = DatabaseState.CONNECTING
        logger.info(TranslationService.tr("database", "database.connection.connecting"))

        val connectionString = "mongodb://${credentials.username}:${credentials.password}@${credentials.address()}/${credentials.database}"

        try {

            val settings = MongoClientSettings.builder()
                .applyConnectionString(ConnectionString(connectionString))
                .build()

            client = MongoClients.create(settings)
            this.executor = MongoExecutor(client!!.getDatabase(credentials.database))

            state = DatabaseState.CONNECTED

            logger.info(
                TranslationService.tr(
                    "database",
                    "database.connection.established",
                    "driver" to "mongodb"
                )
            )

        } catch (_: Exception) {
            state = DatabaseState.FAILED
            logger.error(TranslationService.tr("database", "database.connection.failed", "url" to connectionString))
        }
    }

    override fun executor() = executor!!

    override fun close() {
        if (state == DatabaseState.CLOSED) {
            logger.trWarn("database", "database.connection.already_closed")
            return
        }

        val c = client ?: return

        try {
            logger.trInfo("database", "database.connection.shutdown")

            c.close()
            client = null
            state = DatabaseState.CLOSED

            logger.trInfo("database", "database.connection.closed.success")

        } catch (e: Exception) {
            logger.trError("database", "database.connection.close.failed", e)
        }
    }
}
