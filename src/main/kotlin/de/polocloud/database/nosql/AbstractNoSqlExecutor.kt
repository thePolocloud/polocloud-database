package de.polocloud.database.nosql

import de.polocloud.database.*

abstract class AbstractNoSqlExecutor : DatabaseExecutor {

    protected abstract fun write(
        collection: String,
        identifier: String,
        json: String
    )

    protected abstract fun readAll(
        collection: String
    ): List<String>

    protected abstract fun deleteInternal(
        collection: String,
        identifier: String
    )

    protected abstract fun existsInternal(
        collection: String,
        identifier: String
    ): Boolean

    protected abstract fun destroyInternal(
        collection: String
    )

    override fun <T : Any> save(key: DatabaseKey<T>, value: T) {
        val idField = findIdentifierField(value::class.java.declaredFields)
            ?: throw IllegalStateException("No EntryIdentifier found")

        idField.isAccessible = true
        val identifier = idField.get(value).toString()

        val json = DatabaseSerializer.serialize(value, key.clazz)

        write(key.id(), identifier, json)
    }


    override fun <T : Any> findAll(key: DatabaseKey<T>): List<T> {
        return readAll(key.id()).map {
            DatabaseSerializer.deserialize(it, key.clazz)
        }
    }

    override fun <T : Any> delete(key: DatabaseKey<T>, value: T) {

        val idField = findIdentifierField(value::class.java.declaredFields)
            ?: return

        idField.isAccessible = true
        val identifier = idField.get(value).toString()

        deleteInternal(key.id(), identifier)
    }

    override fun destroy(key: DatabaseKey<*>) {
        destroyInternal(key.id())
    }

    override fun <T : Any> exists(key: DatabaseKey<T>, value: T): Boolean {
        val idField = findIdentifierField(value::class.java.declaredFields) ?: return false

        idField.isAccessible = true
        val identifier = idField.get(value).toString()
        return existsInternal(key.id(), identifier)
    }
}
