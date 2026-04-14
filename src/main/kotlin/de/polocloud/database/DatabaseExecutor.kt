package de.polocloud.database

import de.polocloud.database.filtering.Filter
import de.polocloud.database.filtering.FilterTranslator

/**
 * Generic executor interface for performing CRUD operations on a database table or collection.
 *
 * Each method targets a specific table/collection identified by a [DatabaseKey].
 * Implementations exist for SQL databases (via [de.polocloud.database.sql.SqlExecutor])
 * and NoSQL backends (MongoDB, Redis).
 */
interface DatabaseExecutor {

    /**
     * Inserts or updates an entity in the table associated with the given [DatabaseKey].
     *
     * If an entity with the same primary key already exists, it is updated.
     * Otherwise a new row/document is inserted.
     *
     * @param key   the [DatabaseKey] representing the table and entity type
     * @param value the entity to persist
     */
    fun <T : Any> save(key: DatabaseKey<T>, value: T)

    /**
     * Retrieves all entities from the table associated with the given [DatabaseKey].
     *
     * @param key the [DatabaseKey] representing the table and entity type
     * @return list of all entities; empty list if none exist
     */
    fun <T : Any> findAll(key: DatabaseKey<T>): List<T>

    /**
     * Retrieves a single entity by its primary key value.
     *
     * @param key the [DatabaseKey] representing the table and entity type
     * @param id  the primary key value; must match the field annotated with [EntryIdentifier]
     * @return the matching entity, or `null` if not found
     */
    fun <T : Any> findById(key: DatabaseKey<T>, id: Any): T?

    /**
     * Deletes an entity from the table.
     *
     * The entity to delete is identified via the field annotated with [EntryIdentifier].
     *
     * @param key   the [DatabaseKey] representing the table
     * @param value the entity instance to delete
     */
    fun <T : Any> delete(key: DatabaseKey<T>, value: T)

    /**
     * Drops the entire table or collection associated with the given [DatabaseKey].
     *
     * @param key the [DatabaseKey] whose backing storage should be destroyed
     */
    fun destroy(key: DatabaseKey<*>)

    /**
     * Checks whether an entity exists in the database.
     *
     * The lookup is performed using the field annotated with [EntryIdentifier].
     *
     * @param key   the [DatabaseKey] representing the table
     * @param value the entity whose primary key is checked
     * @return `true` if an entity with the same primary key exists, `false` otherwise
     */
    fun <T : Any> exists(key: DatabaseKey<T>, value: T): Boolean

    /**
     * Retrieves all entities matching the given filters.
     *
     * Filters are translated into the native query format of the underlying database
     * (e.g. SQL WHERE clause, MongoDB query document). If no filters are provided,
     * all entities are returned.
     *
     * @param key     the [DatabaseKey] representing the table and entity type
     * @param filters zero or more filters to restrict the result set
     * @return list of matching entities; empty list if none match
     */
    fun <T : Any> find(key: DatabaseKey<T>, vararg filters: Filter): List<T>

    /**
     * Returns the [FilterTranslator] used by this executor to convert [Filter] instances
     * into the native query format of the underlying database.
     */
    fun filterTranslator(): FilterTranslator<*>

    /**
     * Returns the first field in [fields] that is annotated with [EntryIdentifier],
     * or `null` if no such field exists.
     *
     * @param fields the declared fields of an entity class
     */
    fun findIdentifierField(fields: Array<java.lang.reflect.Field>): java.lang.reflect.Field? {
        return fields.find { field ->
            field.getAnnotation(EntryIdentifier::class.java) != null ||
                    field.annotations.any { it.annotationClass.java == EntryIdentifier::class.java }
        }
    }

    /**
     * Counts entities in the table associated with the given [DatabaseKey].
     *
     * If no filters are provided, all rows are counted. Otherwise only rows
     * matching all supplied filters are included.
     *
     * @param key     the [DatabaseKey] representing the table and entity type
     * @param filters optional filters to restrict which rows are counted
     * @return the number of matching rows
     */
    fun <T : Any> count(key: DatabaseKey<T>, vararg filters: Filter): Long
}