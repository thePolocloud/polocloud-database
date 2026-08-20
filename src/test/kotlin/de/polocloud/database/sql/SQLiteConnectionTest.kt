package de.polocloud.database.sql

import de.polocloud.database.DatabaseAccess
import de.polocloud.database.DatabaseCredentials
import de.polocloud.i18n.api.TranslationService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.sql.DriverManager

/**
 * Unlike SQL Server/Oracle/CockroachDB (which need a real server and are only covered by
 * [SqlConnectionFactoryUrlTest]'s URL-string checks), SQLite is embedded like H2, so its actual
 * driver wiring through [DatabaseAccess] can be exercised end-to-end without any external service.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SQLiteConnectionTest {

    private lateinit var workDir: File

    @BeforeAll
    fun initTranslations() {
        TranslationService.init()
        TranslationService.preload("database")
    }

    @BeforeEach
    fun setUp() {
        workDir = File("sqlite/${System.nanoTime()}").apply { mkdirs() }
    }

    @AfterEach
    fun tearDown() {
        DatabaseAccess.close()
        workDir.deleteRecursively()
    }

    @Test
    fun `connects and round-trips data through the real sqlite-jdbc driver`() {
        val dbPath = File(workDir, "database.db").path

        DatabaseAccess.initialize(DatabaseCredentials.SQLite(dbPath))
        assertTrue(DatabaseAccess.connect(), "DatabaseAccess must connect against SQLite")
        assertTrue(File(dbPath).exists(), "sqlite-jdbc must have created the file at the literal path")

        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE players (id INTEGER PRIMARY KEY, name TEXT)")
                st.execute("INSERT INTO players VALUES (1, 'steve')")
            }

            conn.createStatement().executeQuery("SELECT name FROM players WHERE id = 1").use { rs ->
                assertTrue(rs.next())
                assertEquals("steve", rs.getString("name"))
            }
        }

        // checkpoint() is an H2-only durability hint - must be a harmless no-op for every other backend.
        DatabaseAccess.checkpoint()
    }
}
