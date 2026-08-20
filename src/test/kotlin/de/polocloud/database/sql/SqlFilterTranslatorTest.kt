package de.polocloud.database.sql

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

class SqlFilterTranslatorTest {

    private val translator = SqlFilterTranslator()

    @Test
    fun `comparison filters translate to a single placeholder clause`() {
        assertEquals(SqlFilterTranslation("age = ?", listOf(30)), translator.translate(Eq("age", 30)))
        assertEquals(SqlFilterTranslation("age <> ?", listOf(30)), translator.translate(NotEq("age", 30)))
        assertEquals(SqlFilterTranslation("age > ?", listOf(30)), translator.translate(GreaterThan("age", 30)))
        assertEquals(SqlFilterTranslation("age >= ?", listOf(30)), translator.translate(GreaterThanOrEq("age", 30)))
        assertEquals(SqlFilterTranslation("age < ?", listOf(30)), translator.translate(LessThan("age", 30)))
        assertEquals(SqlFilterTranslation("age <= ?", listOf(30)), translator.translate(LessThanOrEq("age", 30)))
    }

    @Test
    fun `Between produces a BETWEEN clause with both bounds as parameters`() {
        assertEquals(
            SqlFilterTranslation("age BETWEEN ? AND ?", listOf(18, 65)),
            translator.translate(Between("age", 18, 65))
        )
    }

    @Test
    fun `In and NotIn expand one placeholder per value`() {
        assertEquals(
            SqlFilterTranslation("id IN (?,?,?)", listOf(1, 2, 3)),
            translator.translate(In("id", listOf(1, 2, 3)))
        )
        assertEquals(
            SqlFilterTranslation("id NOT IN (?,?)", listOf(1, 2)),
            translator.translate(NotIn("id", listOf(1, 2)))
        )
    }

    @Test
    fun `string predicates translate to LIKE with wildcards positioned correctly`() {
        assertEquals(SqlFilterTranslation("name LIKE ?", listOf("%steve%")), translator.translate(Like("name", "%steve%")))
        assertEquals(SqlFilterTranslation("name LIKE ?", listOf("steve%")), translator.translate(StartsWith("name", "steve")))
        assertEquals(SqlFilterTranslation("name LIKE ?", listOf("%steve")), translator.translate(EndsWith("name", "steve")))
        assertEquals(SqlFilterTranslation("name LIKE ?", listOf("%steve%")), translator.translate(Contains("name", "steve")))
    }

    @Test
    fun `IsNull and IsNotNull carry no parameters`() {
        assertEquals(SqlFilterTranslation("nickname IS NULL", emptyList()), translator.translate(IsNull("nickname")))
        assertEquals(SqlFilterTranslation("nickname IS NOT NULL", emptyList()), translator.translate(IsNotNull("nickname")))
    }

    @Test
    fun `And and Or parenthesize and join sub-clauses, concatenating parameters in order`() {
        val filter = And(listOf(Eq("name", "steve"), GreaterThan("age", 18)))
        assertEquals(SqlFilterTranslation("(name = ?) AND (age > ?)", listOf("steve", 18)), translator.translate(filter))

        val orFilter = Or(listOf(Eq("name", "steve"), Eq("name", "alex")))
        assertEquals(SqlFilterTranslation("(name = ?) OR (name = ?)", listOf("steve", "alex")), translator.translate(orFilter))
    }

    @Test
    fun `Not wraps the translated clause`() {
        assertEquals(SqlFilterTranslation("NOT (age > ?)", listOf(18)), translator.translate(Not(GreaterThan("age", 18))))
    }

    @Test
    fun `Nor ANDs together the negation of every sub-filter`() {
        val filter = Nor(listOf(Eq("name", "steve"), Eq("name", "alex")))
        assertEquals(
            SqlFilterTranslation("NOT (name = ?) AND NOT (name = ?)", listOf("steve", "alex")),
            translator.translate(filter)
        )
    }

    @Test
    fun `nested boolean filters compose correctly`() {
        val filter = And(
            listOf(
                Or(listOf(Eq("role", "OWNER"), Eq("role", "ADMIN"))),
                GreaterThanOrEq("age", 18)
            )
        )

        assertEquals(
            SqlFilterTranslation("((role = ?) OR (role = ?)) AND (age >= ?)", listOf("OWNER", "ADMIN", 18)),
            translator.translate(filter)
        )
    }
}
