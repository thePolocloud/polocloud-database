package de.polocloud.database.sql

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import de.polocloud.i18n.api.TranslationService
import de.polocloud.database.DatabaseConnectionFactory
import de.polocloud.database.DatabaseCredentials
import de.polocloud.database.DatabaseState
import de.polocloud.i18n.api.trError
import de.polocloud.i18n.api.trWarn

/**
 * SQL connection factory backed by a HikariCP connection pool.
 *
 * Supports PostgreSQL, MySQL, MariaDB, SQL Server, Oracle, CockroachDB, SQLite, and H2 databases.
 * The JDBC URL is constructed automatically from the supplied [DatabaseCredentials].
 *
 * @see de.polocloud.database.DatabaseConnectionFactory
 */
class SqlConnectionFactory(credentials: DatabaseCredentials) :
    DatabaseConnectionFactory<DatabaseCredentials>(credentials) {

    private val executor = SqlExecutor(this)

    @Volatile
    var dataSource: HikariDataSource? = null

    @Volatile
    private var isH2 = false
    private var h2Path: String? = null
    private var h2JdbcUrl: String? = null

    /** Guards rebuilding [dataSource] so concurrent callers don't race to recover at once. */
    private val recoveryLock = Any()

    /**
     * Connects to the SQL database using the provided credentials.
     *
     * @param credentials The SQL database credentials.
     */
    override fun connect(credentials: DatabaseCredentials) {
        this.isH2 = credentials is DatabaseCredentials.H2
        this.h2Path = (credentials as? DatabaseCredentials.H2)?.path
        this.recoveryNotice = null

        val jdbcUrl = buildJdbcUrl(credentials) ?: run {
            logger.error(
                TranslationService.tr(
                    "database",
                    "database.connection.unsupported",
                    "type" to credentials.javaClass.simpleName
                )
            )
            return
        }

        this.h2JdbcUrl = jdbcUrl
        this.state = DatabaseState.CONNECTING
        logger.info(TranslationService.tr("database", "database.connection.connecting"))

        try {
            this.dataSource = createHikariDataSource(
                jdbcUrl = jdbcUrl,
                username = when (credentials) {
                    is DatabaseCredentials.DatabaseRelated -> credentials.username
                    is DatabaseCredentials.H2 -> "sa"
                    is DatabaseCredentials.SQLite -> ""
                    // Unreachable: Redis always uses RedisConnectionFactory, never this class.
                    is DatabaseCredentials.Redis -> ""
                },
                password = when (credentials) {
                    is DatabaseCredentials.DatabaseRelated -> credentials.password
                    is DatabaseCredentials.H2 -> ""
                    is DatabaseCredentials.SQLite -> ""
                    is DatabaseCredentials.Redis -> ""
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
        } catch (ex: Exception) {
            if (recoverFromCorruption(ex)) {
                logger.info(
                    TranslationService.tr(
                        "database",
                        "database.connection.established",
                        "driver" to credentials.javaClass.simpleName.lowercase()
                    )
                )
                return
            }

            this.state = DatabaseState.FAILED
            logger.error(TranslationService.tr("database", "database.connection.failed", "url" to jdbcUrl))
        }
    }

    override fun executor() = executor

    /**
     * Builds the JDBC URL for [credentials]. `null` means the credential type isn't backed by
     * this factory at all (e.g. [DatabaseCredentials.Redis], which always uses
     * [de.polocloud.database.nosql.redis.RedisConnectionFactory] instead).
     *
     * Most [DatabaseCredentials.DatabaseRelated] types share the same `jdbc:<name>://host:port/db`
     * shape (driven by the credential class's own name), but a few real-world drivers deviate
     * from it and need their own branch *before* the generic one: [DatabaseCredentials.CockroachDB]
     * reuses the PostgreSQL wire protocol/driver outright, [DatabaseCredentials.SqlServer] uses
     * `;databaseName=` instead of a `/db` path segment, and [DatabaseCredentials.Oracle] addresses
     * a service name via `@//host:port/service`. [DatabaseCredentials.H2] and
     * [DatabaseCredentials.SQLite] are embedded/file-based and never fit the host:port shape at all.
     */
    internal fun buildJdbcUrl(credentials: DatabaseCredentials): String? = when (credentials) {
        is DatabaseCredentials.CockroachDB -> "jdbc:postgresql://${credentials.address()}/${credentials.database}"
        is DatabaseCredentials.SqlServer ->
            "jdbc:sqlserver://${credentials.address()};databaseName=${credentials.database};encrypt=true;trustServerCertificate=true"
        is DatabaseCredentials.Oracle -> "jdbc:oracle:thin:@//${credentials.address()}/${credentials.database}"
        is DatabaseCredentials.DatabaseRelated -> "jdbc:${credentials.javaClass.simpleName.lowercase()}://${credentials.address()}/${credentials.database}"
        // WRITE_DELAY=0 skips H2's default 500ms write-buffering window, so every commit is
        // flushed to disk immediately - shrinks the window a crash can leave the store corrupted in.
        is DatabaseCredentials.H2 -> "jdbc:h2:file:./${credentials.path};DB_CLOSE_ON_EXIT=FALSE;DB_CLOSE_DELAY=-1;WRITE_DELAY=0"
        is DatabaseCredentials.SQLite -> "jdbc:sqlite:${credentials.path}"
        is DatabaseCredentials.Redis -> null
    }

    /**
     * Rebuilds [dataSource] after H2 file corruption (error 90030), whether it surfaced during
     * the initial [connect] or - via [SqlExecutor] - mid-operation on an already-running pool.
     *
     * Thread-safe: if several operations hit corrupted reads/writes concurrently, only the first
     * one actually runs [H2Recovery]; the rest block on [recoveryLock] and, once it releases,
     * find the pool already rebuilt (checked via a cheap `SELECT 1` probe) and return immediately.
     *
     * @return true if [dataSource] now points at a usable (repaired/recovered/empty) database and
     * the caller should retry its operation; false if [ex] wasn't H2 corruption, or recovery
     * itself failed, in which case [ex] should be treated as a normal, non-recoverable failure.
     */
    fun recoverFromCorruption(ex: Throwable): Boolean {
        if (!isH2 || !H2Recovery.isCorruption(ex)) return false
        val path = h2Path ?: return false
        val url = h2JdbcUrl ?: return false

        synchronized(recoveryLock) {
            dataSource?.let {
                if (probe(it)) {
                    state = DatabaseState.CONNECTED
                    return true
                }
            }

            logger.trWarn("database", "database.h2.recovery.runtime_triggered", "path" to path)

            dataSource?.close()
            dataSource = null
            state = DatabaseState.FAILED

            val outcome = H2Recovery.tryRecover(path, logger) ?: return false

            return try {
                dataSource = createHikariDataSource(jdbcUrl = url, username = "sa", password = "")
                state = DatabaseState.CONNECTED
                recoveryNotice = H2Recovery.toNotice(outcome)
                true
            } catch (_: Exception) {
                state = DatabaseState.FAILED
                false
            }
        }
    }

    private fun probe(ds: HikariDataSource): Boolean = try {
        ds.connection.use { it.createStatement().use { st -> st.execute("SELECT 1") } }
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Forces H2 to flush its write buffer and fsync to disk right away (`CHECKPOINT SYNC`),
     * on top of the `WRITE_DELAY=0` connection setting. No-op for the other SQL backends, which
     * already fsync on every commit.
     *
     * Meant to be called explicitly after infrequent, critical writes (e.g. a group/config
     * change) - not on every operation, since it forces a synchronous disk flush.
     */
    override fun checkpoint() {
        if (!isH2) return

        val ds = dataSource ?: return
        try {
            ds.connection.use { it.createStatement().use { st -> st.execute("CHECKPOINT SYNC") } }
        } catch (e: Exception) {
            logger.trError("database", "database.h2.checkpoint_failed", e)
        }
    }

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
    override fun close() = synchronized(recoveryLock) {
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
