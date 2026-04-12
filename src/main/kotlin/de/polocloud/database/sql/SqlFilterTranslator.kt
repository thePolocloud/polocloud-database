package de.polocloud.database.sql

import de.polocloud.database.filtering.*

class SqlFilterTranslator : FilterTranslator<SqlFilterTranslation> {

    override fun translate(filter: Filter): SqlFilterTranslation {
        return when (filter) {
            is Eq<*> -> SqlFilterTranslation(
                clause = "${filter.field} = ?",
                parameters = listOf(filter.value)
            )

            is NotEq<*> -> SqlFilterTranslation(
                clause = "${filter.field} <> ?",
                parameters = listOf(filter.value)
            )

            is GreaterThan<*> -> SqlFilterTranslation(
                clause = "${filter.field} > ?",
                parameters = listOf(filter.value)
            )

            is GreaterThanOrEq<*> -> SqlFilterTranslation(
                clause = "${filter.field} >= ?",
                parameters = listOf(filter.value)
            )

            is LessThan<*> -> SqlFilterTranslation(
                clause = "${filter.field} < ?",
                parameters = listOf(filter.value)
            )

            is LessThanOrEq<*> -> SqlFilterTranslation(
                clause = "${filter.field} <= ?",
                parameters = listOf(filter.value)
            )

            is Between<*> -> SqlFilterTranslation(
                clause = "${filter.field} BETWEEN ? AND ?",
                parameters = listOf(filter.from, filter.to)
            )

            is In<*> -> SqlFilterTranslation(
                clause = "${filter.field} IN (${filter.values.joinToString(",") { "?" }})",
                parameters = filter.values.toList()
            )

            is NotIn<*> -> SqlFilterTranslation(
                clause = "${filter.field} NOT IN (${filter.values.joinToString(",") { "?" }})",
                parameters = filter.values.toList()
            )

            is Like -> SqlFilterTranslation(
                clause = "${filter.field} LIKE ?",
                parameters = listOf(filter.pattern)
            )

            is StartsWith -> SqlFilterTranslation(
                clause = "${filter.field} LIKE ?",
                parameters = listOf("${filter.value}%")
            )

            is EndsWith -> SqlFilterTranslation(
                clause = "${filter.field} LIKE ?",
                parameters = listOf("%${filter.value}")
            )

            is Contains -> SqlFilterTranslation(
                clause = "${filter.field} LIKE ?",
                parameters = listOf("%${filter.value}%")
            )

            is IsNull -> SqlFilterTranslation(
                clause = "${filter.field} IS NULL",
                parameters = emptyList()
            )

            is IsNotNull -> SqlFilterTranslation(
                clause = "${filter.field} IS NOT NULL",
                parameters = emptyList()
            )

            is And -> {
                val translated = filter.filters.map { translate(it) }
                SqlFilterTranslation(
                    clause = translated.joinToString(" AND ") { "(${it.clause})" },
                    parameters = translated.flatMap { it.parameters }
                )
            }

            is Or -> {
                val translated = filter.filters.map { translate(it) }
                SqlFilterTranslation(
                    clause = translated.joinToString(" OR ") { "(${it.clause})" },
                    parameters = translated.flatMap { it.parameters }
                )
            }

            is Not -> {
                val t = translate(filter.filter)
                SqlFilterTranslation(
                    clause = "NOT (${t.clause})",
                    parameters = t.parameters
                )
            }

            is Nor -> {
                val translated = filter.filters.map { translate(it) }
                val combinedClause = translated.joinToString(" AND ") { "NOT (${it.clause})" }
                val combinedParams = translated.flatMap { it.parameters }
                SqlFilterTranslation(
                    clause = combinedClause,
                    parameters = combinedParams
                )
            }
        }
    }
}