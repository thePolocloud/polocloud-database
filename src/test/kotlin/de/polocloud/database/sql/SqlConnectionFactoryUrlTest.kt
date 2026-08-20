package de.polocloud.database.sql

import de.polocloud.database.DatabaseCredentials
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Pure regression coverage for [SqlConnectionFactory.buildJdbcUrl] - no network/DB server
 * involved, just the URL string each [DatabaseCredentials] type is expected to produce. Several
 * backends (SQL Server, Oracle, CockroachDB) deviate from the generic `jdbc:<name>://host:port/db`
 * shape the other [DatabaseCredentials.DatabaseRelated] types share, which is exactly the kind of
 * thing that's easy to accidentally regress without a real server around to catch it.
 */
class SqlConnectionFactoryUrlTest {

    private val factory = SqlConnectionFactory(DatabaseCredentials.H2("unused"))

    @Test
    fun `generic DatabaseRelated types use jdbc name colon slash slash host colon port slash db`() {
        assertEquals(
            "jdbc:mariadb://db.local:3306/game",
            factory.buildJdbcUrl(DatabaseCredentials.MariaDB("db.local", 3306, "u", "p", "game"))
        )
        assertEquals(
            "jdbc:mysql://db.local:3306/game",
            factory.buildJdbcUrl(DatabaseCredentials.Mysql("db.local", 3306, "u", "p", "game"))
        )
        assertEquals(
            "jdbc:postgresql://db.local:5432/game",
            factory.buildJdbcUrl(DatabaseCredentials.PostgreSQL("db.local", 5432, "u", "p", "game"))
        )
    }

    @Test
    fun `CockroachDB reuses the plain postgresql JDBC URL scheme`() {
        assertEquals(
            "jdbc:postgresql://db.local:26257/game",
            factory.buildJdbcUrl(DatabaseCredentials.CockroachDB("db.local", 26257, "u", "p", "game"))
        )
    }

    @Test
    fun `SQL Server uses databaseName instead of a path segment`() {
        assertEquals(
            "jdbc:sqlserver://db.local:1433;databaseName=game;encrypt=true;trustServerCertificate=true",
            factory.buildJdbcUrl(DatabaseCredentials.SqlServer("db.local", 1433, "u", "p", "game"))
        )
    }

    @Test
    fun `Oracle uses easy-connect service name syntax`() {
        assertEquals(
            "jdbc:oracle:thin:@//db.local:1521/game",
            factory.buildJdbcUrl(DatabaseCredentials.Oracle("db.local", 1521, "u", "p", "game"))
        )
    }

    @Test
    fun `H2 is file-based with recovery-relevant connection params`() {
        assertEquals(
            "jdbc:h2:file:./database;DB_CLOSE_ON_EXIT=FALSE;DB_CLOSE_DELAY=-1;WRITE_DELAY=0",
            factory.buildJdbcUrl(DatabaseCredentials.H2("database"))
        )
    }

    @Test
    fun `SQLite is file-based and takes the path literally`() {
        assertEquals(
            "jdbc:sqlite:database.db",
            factory.buildJdbcUrl(DatabaseCredentials.SQLite("database.db"))
        )
    }

    @Test
    fun `Redis is not backed by SqlConnectionFactory at all`() {
        assertNull(factory.buildJdbcUrl(DatabaseCredentials.Redis("localhost", 6379, "u", "p")))
    }
}
