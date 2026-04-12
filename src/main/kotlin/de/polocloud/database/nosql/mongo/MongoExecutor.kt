package de.polocloud.database.nosql.mongo

import com.mongodb.client.MongoDatabase
import de.polocloud.database.DatabaseKey
import de.polocloud.database.DatabaseSerializer
import de.polocloud.database.filtering.Filter
import de.polocloud.database.filtering.FilterTranslator
import de.polocloud.database.nosql.AbstractNoSqlExecutor
import org.bson.Document

class MongoExecutor(
    private val database: MongoDatabase
) : AbstractNoSqlExecutor() {

    private val filterTranslator = MongoFilterTranslator()

    override fun write(collection: String, identifier: String, json: String) {
        val col = database.getCollection(collection)

        val doc = Document.parse(json)
        doc["_id"] = identifier

        col.replaceOne(
            Document("_id", identifier),
            doc,
            com.mongodb.client.model.ReplaceOptions().upsert(true)
        )
    }

    override fun readAll(collection: String): List<String> {

        val col = database.getCollection(collection)

        return col.find().map { it.toJson() }.toList()
    }

    override fun deleteInternal(collection: String, identifier: String) {
        database.getCollection(collection)
            .deleteOne(Document("_id", identifier))
    }

    override fun existsInternal(collection: String, identifier: String): Boolean {
        return database.getCollection(collection)
            .find(Document("_id", identifier))
            .first() != null
    }

    override fun destroyInternal(collection: String) {
        database.getCollection(collection).drop()
    }

    override fun <T : Any> findById(key: DatabaseKey<T>, id: Any): T? {
        val collection = database.getCollection(key.id())

        val document = collection
            .find(Document("_id", id.toString()))
            .first() ?: return null

        return DatabaseSerializer.deserialize(
            document.toJson(),
            key.clazz
        )
    }

    /**
     * Finds documents in the collection associated with the given [DatabaseKey]
     * using the provided filters.
     *
     * If no filters are provided, all documents in the collection are returned.
     * If one or more [Filter]s are provided, they are combined using a logical AND
     * operation and translated into a MongoDB query document using the
     * configured [MongoFilterTranslator].
     *
     * @param key the database key representing the collection and target type
     * @param filters optional filters to apply to the query
     * @return a list of matching entities
     */
    override fun <T : Any> find(
        key: DatabaseKey<T>,
        vararg filters: Filter
    ): List<T> {

        val collection = database.getCollection(key.id())

        val query = when {
            filters.isEmpty() -> Document()
            filters.size == 1 -> filterTranslator.translate(filters[0])
            else -> filterTranslator.translate(
                de.polocloud.database.filtering.And(filters.toList())
            )
        }

        return collection.find(query)
            .map { DatabaseSerializer.deserialize(it.toJson(), key.clazz) }
            .toList()
    }

    override fun filterTranslator() = filterTranslator

    /**
     * Counts the number of documents in the collection associated with the given [DatabaseKey].
     *
     * If no filters are provided, this method returns the total number of documents
     * in the collection. If one or more [Filter]s are provided, only documents matching
     * the given conditions will be counted.
     *
     * The filters are translated into MongoDB query documents using the configured
     * [MongoFilterTranslator].
     *
     * @param key the database key representing the collection and entity type
     * @param filters optional filters to restrict which documents are counted
     * @return the number of matching documents
     */
    override fun <T : Any> count(
        key: DatabaseKey<T>,
        vararg filters: Filter
    ): Long {

        val collection = database.getCollection(key.id())

        // No filters → count all documents
        if (filters.isEmpty()) {
            return collection.countDocuments()
        }

        val query = if (filters.size == 1) {
            filterTranslator.translate(filters[0])
        } else {
            filterTranslator.translate(de.polocloud.database.filtering.And(filters.toList()))
        }

        return collection.countDocuments(query)
    }

}
