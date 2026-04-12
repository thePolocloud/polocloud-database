package de.polocloud.database

import de.polocloud.database.filtering.Filter
import de.polocloud.database.filtering.FilterTranslator

/**
 * A generic executor interface for performing CRUD operations on database tables.
 *
 * Implementations should provide mechanisms for querying, updating, deleting,
 * and destroying database tables based on a [de.polocloud.database.DatabaseKey].
 */
interface DatabaseExecutor {

    /**
     * Inserts a new row or update into the table associated with the given [dev.httpmarco.polocloud.database.DatabaseKey].
     *
     * @param key The [dev.httpmarco.polocloud.database.DatabaseKey] representing the table and target type.
     * @param value The object to be inserted as a new row in the database.
     */
    fun <T : Any> save(key: DatabaseKey<T>, value: T)

    /**
     * Retrieves all rows from the table associated with the given [dev.httpmarco.polocloud.database.DatabaseKey].
     *
     * @param key The [dev.httpmarco.polocloud.database.DatabaseKey] representing the table and target type.
     * @return A list of objects of type [T] mapped from the database rows.
     */
    fun <T : Any> findAll(key: DatabaseKey<T>): List<T>

    /**
     * Finds a single row in the table associated with the given [dev.httpmarco.polocloud.database.DatabaseKey] by its identifier.
     *
     * @param key The [dev.httpmarco.polocloud.database.DatabaseKey] representing the table and target type.
     * @param id The identifier value used to find the specific row. This should correspond to
     * @return An object of type [T] if a matching row is found, or null if no such row exists.
     */
    fun <T : Any> findById(key: DatabaseKey<T>, id: Any): T?

    /**
     * Deletes a row from the table associated with the given [dev.httpmarco.polocloud.database.DatabaseKey].
     *
     * The row to delete is identified using the field annotated with
     * [EntryIdentifier] in the object [value].
     *
     * @param key The [dev.httpmarco.polocloud.database.DatabaseKey] representing the table.
     * @param value The object representing the row to delete.
     */
    fun <T : Any> delete(key: DatabaseKey<T>, value: T)

    /**
     * Destroys the resources associated with a specific [dev.httpmarco.polocloud.database.DatabaseKey].
     *
     * Typically, this involves dropping the table from the database.
     *
     * @param key The [dev.httpmarco.polocloud.database.DatabaseKey] whose resources should be destroyed.
     */
    fun destroy(key: DatabaseKey<*>)

    /**
     * Checks if a row exists in the table associated with the given [dev.httpmarco.polocloud.database.DatabaseKey].
     *
     * The existence check is typically performed using the field annotated with [EntryIdentifier] in the object
     * represented by the [DatabaseKey].
     *
     * @return true if a matching row exists, false otherwise.
     */
    fun <T : Any> exists(key: DatabaseKey<T>, value: T) : Boolean

    /**
     * Finds a single row in the table associated with the given [dev.httpmarco.polocloud.database.DatabaseKey] using the provided filters.
     *
     * The filters are applied to the query to narrow down the search results. The specific implementation of how filters are applied
     * depends on the underlying database and its query capabilities. For example, in a MongoDB implementation, the filters might be translated into MongoDB query filters, while in a SQL-based implementation, they might be translated into WHERE clauses
     * The method returns an object of type [T] if a matching row is found, or null if no such row exists.
     *
     * @param key The [dev.httpmarco.polocloud.database.DatabaseKey] representing the table and target type.
     * @param filters Vararg parameter representing the filters to apply to the query.
     * @return An object of type [T] if a matching row is found, or null if no such row exists.
     */
    fun <T : Any> find(key: DatabaseKey<T>, vararg filters: Filter): List<T>

    /**
     * Provides a [FilterTranslator] that can translate the generic filter type [Q] into the specific filter format required by the underlying database.
     */
    fun filterTranslator() : FilterTranslator<*>

    /**
     * Finds the field in the given array of fields that is annotated with [EntryIdentifier].
     */
    fun findIdentifierField(fields: Array<java.lang.reflect.Field>): java.lang.reflect.Field? {
        return fields.find { field ->
            field.getAnnotation(EntryIdentifier::class.java) != null ||
                    field.annotations.any { it.annotationClass.java == EntryIdentifier::class.java }
        }
    }

    /**
     * Counts the number of rows in the table associated with the given [DatabaseKey],
     * optionally filtered by the provided [Filter]s.
     *
     * @param key The [DatabaseKey] representing the table and target type.
     * @param filters Optional filters to restrict which rows are counted.
     * @return The number of matching rows.
     */
    fun <T : Any> count(key: DatabaseKey<T>, vararg filters: Filter): Long
}