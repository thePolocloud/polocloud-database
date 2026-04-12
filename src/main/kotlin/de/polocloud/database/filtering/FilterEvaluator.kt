package de.polocloud.database.filtering

object FilterEvaluator {

    fun matches(entity: Any, filter: Filter): Boolean {
        return when (filter) {

            is Eq<*> -> get(entity, filter.field) == filter.value

            is NotEq<*> -> get(entity, filter.field) != filter.value

            is GreaterThan<*> -> compare(entity, filter.field, filter.value) > 0

            is GreaterThanOrEq<*> -> compare(entity, filter.field, filter.value) >= 0

            is LessThan<*> -> compare(entity, filter.field, filter.value) < 0

            is LessThanOrEq<*> -> compare(entity, filter.field, filter.value) <= 0

            is Between<*> -> {
                val v = get(entity, filter.field) as Comparable<Any>
                v >= filter.from && v <= filter.to
            }

            is In<*> -> filter.values.contains(get(entity, filter.field))

            is NotIn<*> -> !filter.values.contains(get(entity, filter.field))

            is Like -> get(entity, filter.field).toString().contains(filter.pattern)

            is StartsWith -> get(entity, filter.field).toString().startsWith(filter.value)

            is EndsWith -> get(entity, filter.field).toString().endsWith(filter.value)

            is Contains -> get(entity, filter.field).toString().contains(filter.value)

            is IsNull -> get(entity, filter.field) == null

            is IsNotNull -> get(entity, filter.field) != null

            is And -> filter.filters.all { matches(entity, it) }

            is Or -> filter.filters.any { matches(entity, it) }

            is Not -> !matches(entity, filter.filter)

            is Nor -> filter.filters.none { matches(entity, it) }
        }
    }

    private fun get(entity: Any, fieldName: String): Any? {
        val field = entity::class.java.declaredFields.firstOrNull {
            it.name == fieldName
        } ?: return null

        field.isAccessible = true
        return field.get(entity)
    }

    @Suppress("UNCHECKED_CAST")
    private fun compare(entity: Any, fieldName: String, value: Any): Int {
        val fieldValue = get(entity, fieldName) as? Comparable<Any>
            ?: throw IllegalArgumentException("Field $fieldName is not comparable")

        return fieldValue.compareTo(value)
    }
}