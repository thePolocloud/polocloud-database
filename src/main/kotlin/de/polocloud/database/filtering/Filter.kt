package de.polocloud.database.filtering

sealed interface Filter

data class Eq<T>(
    val field: String,
    val value: T
) : Filter

data class NotEq<T>(
    val field: String,
    val value: T
) : Filter

data class GreaterThan<T : Comparable<T>>(
    val field: String,
    val value: T
) : Filter

data class GreaterThanOrEq<T : Comparable<T>>(
    val field: String,
    val value: T
) : Filter

data class LessThan<T : Comparable<T>>(
    val field: String,
    val value: T
) : Filter

data class LessThanOrEq<T : Comparable<T>>(
    val field: String,
    val value: T
) : Filter

data class Between<T : Comparable<T>>(
    val field: String,
    val from: T,
    val to: T
) : Filter

data class In<T>(
    val field: String,
    val values: Collection<T>
) : Filter

data class NotIn<T>(
    val field: String,
    val values: Collection<T>
) : Filter

data class Like(
    val field: String,
    val pattern: String
) : Filter

data class StartsWith(
    val field: String,
    val value: String
) : Filter

data class EndsWith(
    val field: String,
    val value: String
) : Filter

data class Contains(
    val field: String,
    val value: String
) : Filter

data class IsNull(
    val field: String
) : Filter

data class IsNotNull(
    val field: String
) : Filter

data class And(
    val filters: List<Filter>
) : Filter

data class Or(
    val filters: List<Filter>
) : Filter

data class Not(
    val filter: Filter
) : Filter

data class Nor(
    val filters: List<Filter>
) : Filter