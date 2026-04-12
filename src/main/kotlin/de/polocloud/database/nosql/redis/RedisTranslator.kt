package de.polocloud.database.nosql.redis

import de.polocloud.database.filtering.*

class RedisTranslator : FilterTranslator<String> {

    override fun translate(filter: Filter): String {
        return when (filter) {
            is Eq<*> -> "@${filter.field}:${filter.value}"
            is NotEq<*> -> "-@${filter.field}:${filter.value}"
            is GreaterThan<*> -> "@${filter.field}:[(${filter.value} +inf]"
            is GreaterThanOrEq<*> -> "@${filter.field}:[${filter.value} +inf]"
            is LessThan<*> -> "@${filter.field}:[-inf (${filter.value}]"
            is LessThanOrEq<*> -> "@${filter.field}:[-inf ${filter.value}]"
            is Between<*> -> "@${filter.field}:[${filter.from} ${filter.to}]"
            is In<*> -> filter.values.joinToString(" | ", "(", ")") { "@${filter.field}:$it" }
            is NotIn<*> -> filter.values.joinToString(" ", "(", ")") { "-@${filter.field}:$it" }
            is Like -> "@${filter.field}:${filter.pattern}"
            is StartsWith -> "@${filter.field}:${filter.value}*"
            is EndsWith -> "@${filter.field}:*${filter.value}"
            is Contains -> "@${filter.field}:*${filter.value}*"
            is IsNull -> "-@${filter.field}:*"
            is IsNotNull -> "@${filter.field}:*"
            is And -> filter.filters.joinToString(" ") { translate(it) }
            is Or -> filter.filters.joinToString(" | ") { translate(it) }
            is Not -> "-(${translate(filter.filter)})"
            is Nor -> filter.filters.joinToString(" ") { "-(${translate(it)})" }
        }
    }
}