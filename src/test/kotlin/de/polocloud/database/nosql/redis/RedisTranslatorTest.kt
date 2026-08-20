package de.polocloud.database.nosql.redis

import de.polocloud.database.filtering.And
import de.polocloud.database.filtering.Between
import de.polocloud.database.filtering.Contains
import de.polocloud.database.filtering.EndsWith
import de.polocloud.database.filtering.Eq
import de.polocloud.database.filtering.GreaterThan
import de.polocloud.database.filtering.GreaterThanOrEq
import de.polocloud.database.filtering.In
import de.polocloud.database.filtering.IsNotNull
import de.polocloud.database.filtering.IsNull
import de.polocloud.database.filtering.LessThan
import de.polocloud.database.filtering.LessThanOrEq
import de.polocloud.database.filtering.Like
import de.polocloud.database.filtering.Not
import de.polocloud.database.filtering.NotEq
import de.polocloud.database.filtering.NotIn
import de.polocloud.database.filtering.Nor
import de.polocloud.database.filtering.Or
import de.polocloud.database.filtering.StartsWith
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pure translation coverage for the RediSearch query-string builder - no Redis server needed.
 * Note that [RedisExecutor] itself doesn't actually use this for find()/count(); it filters
 * in-memory via [de.polocloud.database.filtering.FilterEvaluator] instead. This translator is
 * still public API (`filterTranslator()`) and worth keeping correct regardless.
 */
class RedisTranslatorTest {

    private val translator = RedisTranslator()

    @Test
    fun `Eq and NotEq`() {
        assertEquals("@name:steve", translator.translate(Eq("name", "steve")))
        assertEquals("-@name:steve", translator.translate(NotEq("name", "steve")))
    }

    @Test
    fun `range comparisons use RediSearch numeric range syntax`() {
        assertEquals("@age:[(30 +inf]", translator.translate(GreaterThan("age", 30)))
        assertEquals("@age:[30 +inf]", translator.translate(GreaterThanOrEq("age", 30)))
        assertEquals("@age:[-inf (30]", translator.translate(LessThan("age", 30)))
        assertEquals("@age:[-inf 30]", translator.translate(LessThanOrEq("age", 30)))
        assertEquals("@age:[18 65]", translator.translate(Between("age", 18, 65)))
    }

    @Test
    fun `In and NotIn join per-value clauses`() {
        assertEquals("(@id:1 | @id:2)", translator.translate(In("id", listOf(1, 2))))
        assertEquals("(-@id:1 -@id:2)", translator.translate(NotIn("id", listOf(1, 2))))
    }

    @Test
    fun `string predicates use wildcard suffix-prefix syntax`() {
        assertEquals("@name:steve", translator.translate(Like("name", "steve")))
        assertEquals("@name:steve*", translator.translate(StartsWith("name", "steve")))
        assertEquals("@name:*steve", translator.translate(EndsWith("name", "steve")))
        assertEquals("@name:*steve*", translator.translate(Contains("name", "steve")))
    }

    @Test
    fun `IsNull and IsNotNull use wildcard existence checks`() {
        assertEquals("-@nickname:*", translator.translate(IsNull("nickname")))
        assertEquals("@nickname:*", translator.translate(IsNotNull("nickname")))
    }

    @Test
    fun `boolean composition`() {
        val andFilter = And(listOf(Eq("name", "steve"), GreaterThan("age", 18)))
        assertEquals("@name:steve @age:[(18 +inf]", translator.translate(andFilter))

        val orFilter = Or(listOf(Eq("name", "steve"), Eq("name", "alex")))
        assertEquals("@name:steve | @name:alex", translator.translate(orFilter))

        assertEquals("-(@name:steve)", translator.translate(Not(Eq("name", "steve"))))

        val norFilter = Nor(listOf(Eq("name", "steve"), Eq("name", "alex")))
        assertEquals("-(@name:steve) -(@name:alex)", translator.translate(norFilter))
    }
}
