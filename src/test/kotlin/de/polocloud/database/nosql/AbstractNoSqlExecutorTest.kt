package de.polocloud.database.nosql

import de.polocloud.database.DatabaseKey
import de.polocloud.database.DatabaseSerializer
import de.polocloud.database.EntryIdentifier
import de.polocloud.database.filtering.Filter
import de.polocloud.database.filtering.FilterTranslator
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [AbstractNoSqlExecutor] holds the identifier-extraction and JSON round-trip logic shared by
 * both [de.polocloud.database.nosql.mongo.MongoExecutor] and
 * [de.polocloud.database.nosql.redis.RedisExecutor]. Exercised here through a trivial in-memory
 * subclass instead of a real Mongo/Redis server - the four abstract hooks it implements are the
 * only backend-specific part, everything else (save/findAll/delete/exists/destroy) is this
 * class's own logic.
 */
class AbstractNoSqlExecutorTest {

    @Serializable
    private data class Note(
        @EntryIdentifier
        val id: Int,
        val text: String
    )

    private class InMemoryExecutor : AbstractNoSqlExecutor() {
        val store = mutableMapOf<String, MutableMap<String, String>>()

        override fun write(collection: String, identifier: String, json: String) {
            store.getOrPut(collection) { mutableMapOf() }[identifier] = json
        }

        override fun readAll(collection: String): List<String> = store[collection]?.values?.toList() ?: emptyList()

        override fun deleteInternal(collection: String, identifier: String) {
            store[collection]?.remove(identifier)
        }

        override fun existsInternal(collection: String, identifier: String): Boolean =
            store[collection]?.containsKey(identifier) == true

        override fun destroyInternal(collection: String) {
            store.remove(collection)
        }

        override fun <T : Any> findById(key: DatabaseKey<T>, id: Any): T? =
            store[key.id()]?.get(id.toString())?.let { DatabaseSerializer.deserialize(it, key.clazz) }

        override fun <T : Any> find(key: DatabaseKey<T>, vararg filters: Filter): List<T> = findAll(key)

        override fun filterTranslator(): FilterTranslator<*> = throw UnsupportedOperationException("not needed for this test")

        override fun <T : Any> count(key: DatabaseKey<T>, vararg filters: Filter): Long = findAll(key).size.toLong()
    }

    private val key = DatabaseKey(Note::class)
    private lateinit var executor: InMemoryExecutor

    @BeforeEach
    fun setUp() {
        executor = InMemoryExecutor()
    }

    @Test
    fun `save serializes the entity and stores it under its identifier`() {
        executor.save(key, Note(1, "hello"))

        assertEquals("""{"id":1,"text":"hello"}""", executor.store["Note"]?.get("1"))
    }

    @Test
    fun `save on an existing id overwrites rather than duplicates`() {
        executor.save(key, Note(1, "first"))
        executor.save(key, Note(1, "second"))

        assertEquals(listOf(Note(1, "second")), executor.findAll(key))
    }

    @Test
    fun `findAll deserializes every stored entity back to its original shape`() {
        executor.save(key, Note(1, "hello"))
        executor.save(key, Note(2, "world"))

        assertEquals(setOf(Note(1, "hello"), Note(2, "world")), executor.findAll(key).toSet())
    }

    @Test
    fun `exists reflects whether the identifier is present`() {
        val note = Note(1, "hello")
        assertFalse(executor.exists(key, note))

        executor.save(key, note)
        assertTrue(executor.exists(key, note))
    }

    @Test
    fun `delete removes only the targeted entity`() {
        executor.save(key, Note(1, "hello"))
        executor.save(key, Note(2, "world"))

        executor.delete(key, Note(1, "hello"))

        assertNull(executor.findById(key, 1))
        assertEquals(Note(2, "world"), executor.findById(key, 2))
    }

    @Test
    fun `destroy clears the whole collection`() {
        executor.save(key, Note(1, "hello"))
        executor.save(key, Note(2, "world"))

        executor.destroy(key)

        assertTrue(executor.findAll(key).isEmpty())
    }

    @Test
    fun `delete on an entity without a matching EntryIdentifier field is a no-op, not a crash`() {
        // findIdentifierField returns null for a class with no @EntryIdentifier at all.
        data class NoIdentifier(val text: String)

        executor.delete(DatabaseKey(NoIdentifier::class), NoIdentifier("hello"))
    }
}
