package de.polocloud.database.sql

import de.polocloud.database.*
import de.polocloud.database.exeption.FactoryNotPresentException
import de.polocloud.database.filtering.And
import de.polocloud.database.filtering.Eq
import de.polocloud.database.filtering.Filter
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID
import java.sql.Timestamp
import java.time.Instant as JavaInstant
import kotlin.time.Instant as KotlinInstant
import kotlin.time.ExperimentalTime


/**
 * SQL-based implementation of [DatabaseExecutor].
 *
 * Provides automatic table creation and reflection-based entity mapping.
 * The field annotated with [EntryIdentifier] is used as the primary key.
 *
 * - Tables are created on demand if they do not yet exist.
 * - Save performs an INSERT or an UPDATE depending on whether the entity already exists.
 * - Enums and UUIDs are persisted as strings.
 * - [kotlin.time.Instant] values are stored as SQL TIMESTAMP.
 */
class SqlExecutor(
    private val factory: SqlConnectionFactory
) : DatabaseExecutor {

    private val filterTranslator: SqlFilterTranslator = SqlFilterTranslator()
    private val logger: Logger = LogManager.getLogger(SqlExecutor::class.java)

    /**
     * Table names already confirmed to exist, so [ensureTableExists] can skip the metadata
     * round-trip (and the connection checkout that goes with it) on every save/find/count/delete
     * call. Cleared on [destroy] and whenever [withConnection] recovers from H2 corruption, since
     * either can make a previously-known table disappear.
     */
    private val knownTables = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Saves or updates an entity in the database.
     *
     * If an entity with the same primary key already exists,
     * an UPDATE is executed. Otherwise, an INSERT is performed.
     *
     * @param key   the database key describing table and entity type
     * @param value the entity instance to persist
     *
     * @throws IllegalStateException if no [EntryIdentifier] field exists
     */
    override fun <T : Any> save(key: DatabaseKey<T>, value: T) {
        if (!factory.isValid()) {
            throw FactoryNotPresentException()
        }

        ensureTableExists(key)
        val meta = resolveMeta(key)

        val idValue = meta.identifier.get(value)
        val exists = findById(key, idValue) != null

        val values = meta.fields.map { field ->
            val raw = field.get(value)
            if (field.type.isEnum) (raw as Enum<*>).name else raw
        }

        if (exists) {
            val setClause = meta.fields.joinToString(", ") { "${it.name} = ?" }
            val sql = "UPDATE ${key.id()} SET $setClause WHERE ${meta.identifier.name} = ?"
            update(sql, *(values + idValue).toTypedArray())
        } else {
            val columns = meta.fields.joinToString(", ") { it.name }
            val placeholders = meta.fields.joinToString(", ") { "?" }
            val sql = "INSERT INTO ${key.id()} ($columns) VALUES ($placeholders)"
            update(sql, *values.toTypedArray())
        }
    }

    /**
     * Counts the number of rows in the table associated with the given [DatabaseKey].
     *
     * If no filters are provided, this method returns the total number of rows
     * in the table. If one or more [Filter]s are provided, only rows matching
     * the given conditions will be counted.
     *
     * The filters are translated into the underlying SQL WHERE clause using
     * the configured [SqlFilterTranslator].
     *
     * @param key the database key representing the table and entity type
     * @param filters optional filters to restrict which rows are counted
     * @return the number of matching rows
     *
     * @throws FactoryNotPresentException if the connection factory is not valid
     */
    override fun <T : Any> count(
        key: DatabaseKey<T>,
        vararg filters: Filter
    ): Long {

        if (!factory.isValid()) {
            throw FactoryNotPresentException()
        }

        ensureTableExists(key)

        // No filters → count all rows
        if (filters.isEmpty()) {
            return queryOne(
                "SELECT COUNT(*) FROM ${key.id()}",
                mapper = SqlMapper { rs -> rs.getLong(1) }
            ) ?: 0L
        }

        val combined: Filter =
            if (filters.size == 1) filters[0]
            else And(filters.toList())

        val translated = filterTranslator.translate(combined)

        val sql = """
        SELECT COUNT(*) FROM ${key.id()}
        WHERE ${translated.clause}
    """.trimIndent()

        return queryOne(
            sql,
            *translated.parameters
                .map { mapValueForDb(it) }
                .toTypedArray(),
            mapper = SqlMapper { rs -> rs.getLong(1) }
        ) ?: 0L
    }

    /**
     * Retrieves all entities from the table.
     *
     * @param key the database key
     * @return list of mapped entities
     */
    override fun <T : Any> findAll(key: DatabaseKey<T>): List<T> {
        ensureTableExists(key)
        val meta = resolveMeta(key)

        return queryList(
            "SELECT * FROM ${key.id()}",
            mapper = SqlMapper { rs -> mapRow(meta, rs) }
        )
    }

    /**
     * Retrieves a single entity by its primary key.
     *
     * @param key the database key
     * @param id  primary key value
     * @return mapped entity or null if not found
     */
    override fun <T : Any> findById(key: DatabaseKey<T>, id: Any): T? {
        ensureTableExists(key)
        val meta = resolveMeta(key)

        return queryOne(
            "SELECT * FROM ${key.id()} WHERE ${meta.identifier.name} = ? LIMIT 1",
            id,
            mapper = SqlMapper { rs -> mapRow(meta, rs) }
        )
    }

    /**
     * Checks whether an entity exists in the database.
     *
     * @param key   the database key
     * @param value entity instance
     * @return true if present
     */
    override fun <T : Any> exists(key: DatabaseKey<T>, value: T): Boolean {
        val meta = resolveMeta(key)
        val idValue = meta.identifier.get(value)

        return count(
            key,
            Eq(meta.identifier.name, idValue)
        ) > 0
    }

    override fun <T : Any> find(
        key: DatabaseKey<T>,
        vararg filters: Filter
    ): List<T> {

        if (!factory.isValid()) {
            throw FactoryNotPresentException()
        }

        ensureTableExists(key)
        val meta = resolveMeta(key)

        // No filters → return all
        if (filters.isEmpty()) {
            return queryList(
                "SELECT * FROM ${key.id()}",
                mapper = SqlMapper { rs -> mapRow(meta, rs) }
            )
        }

        val combined: Filter =
            if (filters.size == 1) filters[0]
            else And(filters.toList())

        val translated = filterTranslator.translate(combined)

        val sql = """
        SELECT * FROM ${key.id()}
        WHERE ${translated.clause}
    """.trimIndent()

        return queryList(
            sql,
            *translated.parameters
                .map { mapValueForDb(it) }
                .toTypedArray(),
            mapper = SqlMapper { rs -> mapRow(meta, rs) }
        )
    }

    override fun filterTranslator() = filterTranslator

    /**
     * Deletes an entity from the database.
     *
     * @param key   the database key
     * @param value entity instance to delete
     */
    override fun <T : Any> delete(key: DatabaseKey<T>, value: T) {
        ensureTableExists(key)
        val meta = resolveMeta(key)

        val idValue = meta.identifier.get(value)
        update("DELETE FROM ${key.id()} WHERE ${meta.identifier.name} = ?", idValue)
    }

    /**
     * Drops the table represented by the given key.
     *
     * @param key the database key
     */
    override fun destroy(key: DatabaseKey<*>) {
        update("DROP TABLE IF EXISTS ${key.id()}")
        knownTables.remove(key.id())
    }

    private data class EntityMeta<T>(
        val fields: List<Field>,
        val identifier: Field,
        val constructor: Constructor<*>
    )

    private fun <T : Any> resolveMeta(key: DatabaseKey<T>): EntityMeta<T> {
        val clazz = key.clazz.java // important!

        val fields = clazz.declaredFields.toList().onEach { it.isAccessible = true }

        val identifier = fields.find {
            it.getAnnotation(EntryIdentifier::class.java) != null
        } ?: throw IllegalStateException(
            "No @EntryIdentifier field found in ${clazz.simpleName}"
        )

        val constructor = clazz.declaredConstructors.first().apply { isAccessible = true }

        return EntityMeta(fields, identifier, constructor)
    }


    /**
     * Executes an SQL update statement.
     *
     * @param sql    SQL string
     * @param params statement parameters
     * @return affected row count or -1 if invalid
     */
    fun update(sql: String, vararg params: Any?): Int {
        if (!factory.isValid()) {
            throw FactoryNotPresentException()
        }

        return try {
            withConnection { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    params.forEachIndexed { i, p ->
                        stmt.setObject(i + 1, mapValueForDb(p))
                    }
                    stmt.executeUpdate()
                }
            }
        } catch (ex: Exception) {
            logger.error("SQL update failed: $sql", ex)
            0
        }
    }

    /**
     * Executes an SQL query returning multiple results.
     */
    fun <T> queryList(
        sql: String,
        vararg params: Any?,
        mapper: SqlMapper<T>
    ): List<T> = executeQuery(sql, params, mapper)

    /**
     * Executes an SQL query returning a single result.
     */
    fun <T> queryOne(
        sql: String,
        vararg params: Any?,
        mapper: SqlMapper<T>
    ): T? = executeQuery(sql, params, mapper).firstOrNull()

    private fun <T> executeQuery(
        sql: String,
        params: Array<out Any?>,
        mapper: SqlMapper<T>
    ): List<T> {

        if (!factory.isValid()) {
            throw FactoryNotPresentException()
        }

        return try {
            withConnection { conn ->
                val results = mutableListOf<T>()

                conn.prepareStatement(sql).use { stmt ->
                    params.forEachIndexed { i, p ->
                        stmt.setObject(i + 1, p)
                    }

                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            results.add(mapper.map(rs))
                        }
                    }
                }

                results
            }
        } catch (ex: Exception) {
            logger.error("SQL query failed: $sql", ex)
            emptyList()
        }
    }

    /**
     * Runs [action] against a pooled connection. If it fails with what looks like H2 file
     * corruption (see [H2Recovery.isCorruption]) - which can surface mid-operation on an already
     * running pool, not just during the initial [SqlConnectionFactory.connect] - this triggers
     * [SqlConnectionFactory.recoverFromCorruption] and retries [action] once against the rebuilt
     * pool. Any other failure (syntax error, constraint violation, ...) is rethrown unchanged.
     */
    private fun <T> withConnection(action: (java.sql.Connection) -> T): T {
        val ds = factory.dataSource ?: throw IllegalStateException("DataSource not initialized")

        return try {
            ds.connection.use(action)
        } catch (ex: Exception) {
            if (!factory.recoverFromCorruption(ex)) throw ex

            // The recovered store may be repaired, only partially salvaged, or reset to empty -
            // any of which can make a table we previously confirmed exists disappear.
            knownTables.clear()

            val recovered = factory.dataSource ?: throw IllegalStateException("DataSource not initialized")
            recovered.connection.use(action)
        }
    }

    /**
     * Ensures the SQL table for the given entity exists.
     * If not, it will be created dynamically.
     */
    private fun <T : Any> ensureTableExists(key: DatabaseKey<T>) {

        if (!factory.isValid()) {
            throw FactoryNotPresentException()
        }

        if (key.id() in knownTables) return

        try {
            withConnection { conn -> ensureTableExists(conn, key) }
        } catch (ex: SQLException) {
            logger.error("Failed to ensure table exists: ${key.id()}", ex)
        }
    }

    /**
     * Same as [ensureTableExists], but reuses an already-checked-out [conn] instead of asking
     * the pool for a new one. [ensureTableExists] used to check the table on one pooled
     * connection and then run the `CREATE TABLE` (and any `@EntryRef` follow-up calls) through
     * [update], which checks out a *second* connection from the same bounded Hikari pool while
     * the first one was still held. Under enough concurrent first-time callers that second
     * checkout can never succeed - every thread is holding the connection some other thread
     * needs - wedging the whole pool (`active=10, idle=0, waiting=N`) until callers time out.
     * Doing the existence check, the `CREATE TABLE`, and any FK table creation on one connection
     * removes that inner checkout entirely.
     */
    private fun <T : Any> ensureTableExists(conn: java.sql.Connection, key: DatabaseKey<T>) {
        if (key.id() in knownTables) return

        // tableNamePattern is matched against whatever case the driver actually stored the name
        // in, not the case we asked for - e.g. unquoted `CREATE TABLE nodes` is folded to `NODES`
        // by H2 (and Oracle), to lowercase `nodes` by Postgres/CockroachDB, so passing key.id()
        // as the pattern can silently miss an existing table depending on backend. List every
        // table instead and compare case-insensitively.
        val meta = conn.metaData
        val rs = meta.getTables(null, null, null, arrayOf("TABLE"))

        var exists = false
        while (rs.next()) {
            if (key.id().equals(rs.getString("TABLE_NAME"), ignoreCase = true)) {
                exists = true
                break
            }
        }

        if (exists) {
            knownTables.add(key.id())
            return
        }

        val metaInfo = resolveMeta(key)

        val columnDefinitions = metaInfo.fields.joinToString(", ") { field ->
            val type = mapJavaTypeToSql(field.type)
            val pk = if (field == metaInfo.identifier) "PRIMARY KEY" else ""

            val fkAnnotation = field.getAnnotation(EntryRef::class.java)
            val fk = if (fkAnnotation != null) {
                val fkKey = DatabaseKey(fkAnnotation.clazz)
                ensureTableExists(conn, fkKey)
                "REFERENCES ${fkKey.id()}(${resolveMeta(fkKey).identifier.name})"
            } else ""

            "${field.name} $type $pk $fk".trim()
        }

        conn.prepareStatement("CREATE TABLE IF NOT EXISTS ${key.id()} ($columnDefinitions)").use { it.executeUpdate() }
        knownTables.add(key.id())
    }

    @OptIn(ExperimentalTime::class)
    private fun mapValueForDb(value: Any?): Any? {
        return when (value) {
            is KotlinInstant -> Timestamp.from(JavaInstant.ofEpochMilli(value.toEpochMilliseconds()))
            is Enum<*> -> value.name
            else -> value
        }
    }

    private fun <T> mapRow(meta: EntityMeta<T>, rs: ResultSet): T {
        val args = meta.fields.map { field ->
            val value = rs.getObject(field.name)

            when {
                value == null -> null

                field.type.isEnum && value is String ->
                    java.lang.Enum.valueOf(field.type as Class<out Enum<*>>, value)

                field.type.kotlin == KotlinInstant::class -> when (value) {
                    is Timestamp -> KotlinInstant.fromEpochMilliseconds(value.time)
                    is String -> KotlinInstant.parse(value)  // <- String → Instant
                    is JavaInstant -> KotlinInstant.fromEpochMilliseconds(value.toEpochMilli())
                    else -> throw IllegalArgumentException("Cannot convert $value to KotlinInstant")
                }

                else -> value
            }
        }.toTypedArray()

        return meta.constructor.newInstance(*args) as T
    }


    private fun mapJavaTypeToSql(clazz: Class<*>): String =
        when {
            clazz.isEnum -> "VARCHAR(50)"
            clazz.kotlin == Int::class -> "INT"
            clazz.kotlin == Long::class -> "BIGINT"
            clazz.kotlin == String::class -> "VARCHAR(512)"
            clazz.kotlin == Boolean::class -> "BOOLEAN"
            clazz.kotlin == Double::class -> "DOUBLE PRECISION"
            clazz.kotlin == Float::class -> "FLOAT"
            clazz.kotlin == KotlinInstant::class -> "TIMESTAMP"
            clazz == UUID::class.java -> "UUID"
            else -> "TEXT"
        }

    override fun findIdentifierField(fields: Array<Field>): Field? =
        fields.find { it.getAnnotation(EntryIdentifier::class.java) != null }
}
