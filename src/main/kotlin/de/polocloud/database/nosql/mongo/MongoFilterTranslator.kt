package de.polocloud.database.nosql.mongo

import de.polocloud.database.filtering.*
import de.polocloud.database.filtering.Not
import de.polocloud.database.filtering.Nor
import de.polocloud.database.filtering.In
import de.polocloud.database.filtering.NotIn
import org.bson.Document

class MongoFilterTranslator : FilterTranslator<Document> {

    override fun translate(filter: Filter): Document {
        return when (filter) {
            is Eq<*> -> Document(filter.field, filter.value)
            is NotEq<*> -> Document(filter.field, Document("\$ne", filter.value))
            is GreaterThan<*> -> Document(filter.field, Document("\$gt", filter.value))
            is GreaterThanOrEq<*> -> Document(filter.field, Document("\$gte", filter.value))
            is LessThan<*> -> Document(filter.field, Document("\$lt", filter.value))
            is LessThanOrEq<*> -> Document(filter.field, Document("\$lte", filter.value))
            is Between<*> -> Document(filter.field, Document("\$gte", filter.from).append("\$lte", filter.to))
            is In<*> -> Document(filter.field, Document("\$in", filter.values.toList()))
            is NotIn<*> -> Document(filter.field, Document("\$nin", filter.values.toList()))
            is Like -> Document(filter.field, Document("\$regex", filter.pattern))
            is StartsWith -> Document(filter.field, Document("\$regex", "^${filter.value}"))
            is EndsWith -> Document(filter.field, Document("\$regex", "${filter.value}\$"))
            is Contains -> Document(filter.field, Document("\$regex", filter.value))
            is IsNull -> Document(filter.field, Document("\$exists", false))
            is IsNotNull -> Document(filter.field, Document("\$exists", true))
            is And -> Document("\$and", filter.filters.map { translate(it) })
            is Or -> Document("\$or", filter.filters.map { translate(it) })
            is Not -> Document("\$not", translate(filter.filter))
            is Nor -> Document("\$nor", filter.filters.map { translate(it) })
        }
    }
}