package de.polocloud.database.sql

import java.sql.ResultSet

/**
 * Functional interface for mapping a single row of a SQL [ResultSet] to an object of type [T].
 *
 * This is typically used in conjunction with [SqlExecutor.query] to convert query results
 * into Kotlin objects.
 *
 * Example usage:
 * ```
 * val users: List<User> = sqlExecutor.query("SELECT * FROM users") { rs ->
 *     User(
 *         id = rs.getInt("id"),
 *         username = rs.getString("username"),
 *         active = rs.getBoolean("active")
 *     )
 * }
 * ```
 *
 * @param T The type of object to map each row of the ResultSet into.
 */
fun interface SqlMapper<T> {

    /**
     * Maps a single row of the given [ResultSet] to an object of type [T].
     *
     * The [ResultSet] is positioned at the current row, and this method should
     * read the necessary columns and construct an object.
     *
     * @param set The current row of the ResultSet.
     * @return An instance of type [T] representing the current row.
     * @throws java.sql.SQLException If a database access error occurs.
     */
    fun map(set: ResultSet): T
}
