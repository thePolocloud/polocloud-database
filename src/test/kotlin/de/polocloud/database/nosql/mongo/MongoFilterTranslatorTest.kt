package de.polocloud.database.nosql.mongo

import de.polocloud.database.filtering.And
import de.polocloud.database.filtering.Between
import de.polocloud.database.filtering.Contains
import de.polocloud.database.filtering.EndsWith
import de.polocloud.database.filtering.Eq
import de.polocloud.database.filtering.GreaterThan
import de.polocloud.database.filtering.In
import de.polocloud.database.filtering.IsNotNull
import de.polocloud.database.filtering.IsNull
import de.polocloud.database.filtering.Like
import de.polocloud.database.filtering.Not
import de.polocloud.database.filtering.NotEq
import de.polocloud.database.filtering.NotIn
import de.polocloud.database.filtering.Nor
import de.polocloud.database.filtering.Or
import de.polocloud.database.filtering.StartsWith
import org.bson.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pure translation coverage - builds [Document] query filters without ever touching a real
 * MongoDB server. [Document] compares structurally (it's a [Map]), so this catches wrong
 * operator names/nesting exactly like a live `find()` call would surface them.
 */
class MongoFilterTranslatorTest {

    private val translator = MongoFilterTranslator()

    @Test
    fun `Eq is a plain field-value match, no operator`() {
        assertEquals(Document("name", "steve"), translator.translate(Eq("name", "steve")))
    }

    @Test
    fun `comparison filters use the matching Mongo operator`() {
        assertEquals(Document("age", Document("\$ne", 30)), translator.translate(NotEq("age", 30)))
        assertEquals(Document("age", Document("\$gt", 30)), translator.translate(GreaterThan("age", 30)))
        assertEquals(
            Document("age", Document("\$gte", 18).append("\$lte", 65)),
            translator.translate(Between("age", 18, 65))
        )
    }

    @Test
    fun `In and NotIn translate to their list operators`() {
        assertEquals(Document("id", Document("\$in", listOf(1, 2, 3))), translator.translate(In("id", listOf(1, 2, 3))))
        assertEquals(Document("id", Document("\$nin", listOf(1, 2))), translator.translate(NotIn("id", listOf(1, 2))))
    }

    @Test
    fun `string predicates translate to anchored regexes`() {
        assertEquals(Document("name", Document("\$regex", "steve")), translator.translate(Like("name", "steve")))
        assertEquals(Document("name", Document("\$regex", "^steve")), translator.translate(StartsWith("name", "steve")))
        assertEquals(Document("name", Document("\$regex", "steve\$")), translator.translate(EndsWith("name", "steve")))
        assertEquals(Document("name", Document("\$regex", "steve")), translator.translate(Contains("name", "steve")))
    }

    @Test
    fun `IsNull and IsNotNull use the exists operator`() {
        assertEquals(Document("nickname", Document("\$exists", false)), translator.translate(IsNull("nickname")))
        assertEquals(Document("nickname", Document("\$exists", true)), translator.translate(IsNotNull("nickname")))
    }

    @Test
    fun `boolean composition uses the matching Mongo logical operator`() {
        val andFilter = And(listOf(Eq("name", "steve"), GreaterThan("age", 18)))
        assertEquals(
            Document("\$and", listOf(Document("name", "steve"), Document("age", Document("\$gt", 18)))),
            translator.translate(andFilter)
        )

        val orFilter = Or(listOf(Eq("name", "steve"), Eq("name", "alex")))
        assertEquals(
            Document("\$or", listOf(Document("name", "steve"), Document("name", "alex"))),
            translator.translate(orFilter)
        )

        assertEquals(Document("\$not", Document("name", "steve")), translator.translate(Not(Eq("name", "steve"))))

        val norFilter = Nor(listOf(Eq("name", "steve"), Eq("name", "alex")))
        assertEquals(
            Document("\$nor", listOf(Document("name", "steve"), Document("name", "alex"))),
            translator.translate(norFilter)
        )
    }
}
