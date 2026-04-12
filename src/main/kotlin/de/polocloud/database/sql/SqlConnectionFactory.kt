package de.polocloud.database.sql

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import de.polocloud.i18n.api.TranslationService
import de.polocloud.database.DatabaseConnectionFactory
import de.polocloud.database.DatabaseCredentials
import de.polocloud.database.DatabaseState
import de.polocloud.i18n.api.trWarn

/**
 * Abstract SQL connection factory using HikariCP as the connection pool.
 * Provides methods to connect to a SQL database and manage the connection pool.
 *
 * @see dev.httpmarco.polocloud.database.DatabaseConnectionFactory
 */
class SqlConnectionFactory(credentials: DatabaseCredentials) :
    DatabaseConnectionFactory<DatabaseCredentials>(credentials) {

    private val executor = SqlExecutor(this)
    var dataSource: HikariDataSource? = null

    /**
     * Connects to the SQL database using the provided credentials.
     *
     * @param credentials The SQL database credentials.
     */
    override fun connect(credentials: DatabaseCredentials) {
        val jdbcUrl = when (credentials) {
            is DatabaseCredentials.DatabaseRelated -> "jdbc:${credentials.javaClass.simpleName.lowercase()}://${credentials.address()}/${credentials.database}"
            is DatabaseCredentials.H2 -> "jdbc:h2:file:./${credentials.path}"
            else -> {
                logger.error(
                    TranslationService.tr(
                        "database",
                        "database.connection.unsupported",
                        "type" to credentials.javaClass.simpleName
                    )
                )
                return
            }
        }

        this.state = DatabaseState.CONNECTING
        logger.info(TranslationService.tr("database", "database.connection.connecting"))

        try {
            this.dataSource = createHikariDataSource(
                jdbcUrl = jdbcUrl,
                username = when (credentials) {
                    is DatabaseCredentials.DatabaseRelated -> credentials.username
                    is DatabaseCredentials.H2 -> "sa"
                },
                password =  when (credentials) {
                    is DatabaseCredentials.DatabaseRelated -> credentials.password
                    is DatabaseCredentials.H2 -> ""
                }
            )

            this.state = DatabaseState.CONNECTED

            logger.info(
                TranslationService.tr(
                    "database",
                    "database.connection.established",
                    "driver" to credentials.javaClass.simpleName.lowercase()
                )
            )
        } catch (_: Exception) {
            this.state = DatabaseState.FAILED
            logger.error(TranslationService.tr("database", "database.connection.failed", "url" to jdbcUrl))
        }
    }

    override fun executor() = executor

    /**
     * Creates a HikariCP DataSource with the given configuration.
     *
     * @param jdbcUrl JDBC URL of the database.
     * @param username Database username.
     * @param password Database password.
     * @return Initialized HikariCP DataSource.
     */
    fun createHikariDataSource(
        jdbcUrl: String,
        username: String,
        password: String,
    ): HikariDataSource {
        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = username
            this.password = password

            this.maximumPoolSize = 10
            this.minimumIdle = 2
            this.idleTimeout = 600_000
            this.maxLifetime = 1_800_000
            this.connectionTimeout = 30_000
            this.validationTimeout = 5_000
            this.poolName = "PoloCloudPool"
        }

        logger.info(
            TranslationService.tr(
                "database",
                "database.pool.initializing",
                "pool" to config.poolName
            )
        )

        val hikariDataSource = HikariDataSource(config)

        logger.info(
            TranslationService.tr(
                "database",
                "database.pool.initialized",
                "pool" to config.poolName,
                "maxPoolSize" to config.maximumPoolSize
            )
        )

        return hikariDataSource
    }

    /**
     * Closes the DataSource and releases all connections.
     */
    override fun close() {
        val ds = dataSource ?: run {
            logger.trWarn("database", "database.pool.not_initialized")
            return
        }

        logger.info(
            TranslationService.tr(
                "database",
                "database.pool.shutdown",
                "pool" to ds.poolName
            )
        )

        try {
            ds.close()

            state = DatabaseState.CLOSED

            logger.info(
                TranslationService.tr(
                    "database",
                    "database.pool.closed",
                    "pool" to ds.poolName
                )
            )

            logger.info(
                TranslationService.tr(
                    "database",
                    "database.connection.closed"
                )
            )

        } catch (e: Exception) {
            logger.error(
                TranslationService.tr(
                    "database",
                    "database.pool.close_failed",
                    "pool" to ds.poolName
                ),
                e
            )
        } finally {
            dataSource = null
        }
    }
}
