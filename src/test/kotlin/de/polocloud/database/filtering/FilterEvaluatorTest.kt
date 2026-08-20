package de.polocloud.database.filtering

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [FilterEvaluator] is the in-memory filter engine [de.polocloud.database.nosql.redis.RedisExecutor]
 * uses for find()/count() (Redis itself doesn't do arbitrary filtering server-side), so its
 * correctness directly determines query correctness for that backend.
 */
class FilterEvaluatorTest {

    private data class Sample(val name: String, val age: Int, val nickname: String? = null)

    private val steve = Sample("steve", 30, "the builder")
    private val alex = Sample("alex", 25, null)

    @Test
    fun `Eq and NotEq compare by value`() {
        assertTrue(FilterEvaluator.matches(steve, Eq("age", 30)))
        assertFalse(FilterEvaluator.matches(steve, Eq("age", 25)))
        assertTrue(FilterEvaluator.matches(steve, NotEq("age", 25)))
        assertFalse(FilterEvaluator.matches(steve, NotEq("age", 30)))
    }

    @Test
    fun `ordering comparisons work on Comparable fields`() {
        assertTrue(FilterEvaluator.matches(steve, GreaterThan("age", 29)))
        assertFalse(FilterEvaluator.matches(steve, GreaterThan("age", 30)))
        assertTrue(FilterEvaluator.matches(steve, GreaterThanOrEq("age", 30)))
        assertTrue(FilterEvaluator.matches(steve, LessThan("age", 31)))
        assertFalse(FilterEvaluator.matches(steve, LessThan("age", 30)))
        assertTrue(FilterEvaluator.matches(steve, LessThanOrEq("age", 30)))
    }

    @Test
    fun `Between is inclusive on both ends`() {
        assertTrue(FilterEvaluator.matches(steve, Between("age", 30, 40)))
        assertTrue(FilterEvaluator.matches(steve, Between("age", 20, 30)))
        assertFalse(FilterEvaluator.matches(steve, Between("age", 31, 40)))
    }

    @Test
    fun `In and NotIn check collection membership`() {
        assertTrue(FilterEvaluator.matches(steve, In("age", listOf(10, 20, 30))))
        assertFalse(FilterEvaluator.matches(steve, In("age", listOf(10, 20))))
        assertTrue(FilterEvaluator.matches(steve, NotIn("age", listOf(10, 20))))
        assertFalse(FilterEvaluator.matches(steve, NotIn("age", listOf(30))))
    }

    @Test
    fun `string predicates work on the field's toString`() {
        assertTrue(FilterEvaluator.matches(steve, Like("name", "tev")))
        assertFalse(FilterEvaluator.matches(steve, Like("name", "zzz")))
        assertTrue(FilterEvaluator.matches(steve, StartsWith("name", "ste")))
        assertFalse(FilterEvaluator.matches(steve, StartsWith("name", "eve")))
        assertTrue(FilterEvaluator.matches(steve, EndsWith("name", "eve")))
        assertTrue(FilterEvaluator.matches(steve, Contains("name", "tev")))
    }

    @Test
    fun `IsNull and IsNotNull check for a null field value`() {
        assertTrue(FilterEvaluator.matches(alex, IsNull("nickname")))
        assertFalse(FilterEvaluator.matches(steve, IsNull("nickname")))
        assertTrue(FilterEvaluator.matches(steve, IsNotNull("nickname")))
        assertFalse(FilterEvaluator.matches(alex, IsNotNull("nickname")))
    }

    @Test
    fun `IsNull also covers a field that does not exist at all`() {
        assertTrue(FilterEvaluator.matches(steve, IsNull("doesNotExist")))
    }

    @Test
    fun `And requires every sub-filter to match`() {
        val filter = And(listOf(Eq("name", "steve"), GreaterThan("age", 20)))
        assertTrue(FilterEvaluator.matches(steve, filter))
        assertFalse(FilterEvaluator.matches(alex, filter))
    }

    @Test
    fun `Or requires at least one sub-filter to match`() {
        val filter = Or(listOf(Eq("name", "steve"), Eq("name", "alex")))
        assertTrue(FilterEvaluator.matches(steve, filter))
        assertTrue(FilterEvaluator.matches(alex, filter))
        assertFalse(FilterEvaluator.matches(Sample("herobrine", 1000), filter))
    }

    @Test
    fun `Not negates the wrapped filter`() {
        assertTrue(FilterEvaluator.matches(alex, Not(Eq("name", "steve"))))
        assertFalse(FilterEvaluator.matches(steve, Not(Eq("name", "steve"))))
    }

    @Test
    fun `Nor requires none of the sub-filters to match`() {
        val filter = Nor(listOf(Eq("name", "steve"), Eq("name", "alex")))
        assertFalse(FilterEvaluator.matches(steve, filter))
        assertFalse(FilterEvaluator.matches(alex, filter))
        assertTrue(FilterEvaluator.matches(Sample("herobrine", 1000), filter))
    }

    @Test
    fun `compare throws for a non-Comparable field`() {
        data class Weird(val blob: Any)

        val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            FilterEvaluator.matches(Weird(Any()), GreaterThan("blob", 1))
        }
        assertTrue(ex.message!!.contains("blob"))
    }
}
