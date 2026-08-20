package de.polocloud.database.sql

import de.polocloud.database.DatabaseAccess
import de.polocloud.database.DatabaseCredentials
import de.polocloud.database.DatabaseState
import de.polocloud.database.RecoverySeverity
import de.polocloud.i18n.api.TranslationService
import org.h2.api.ErrorCode
import org.h2.mvstore.MVStoreException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import java.io.File
import java.io.RandomAccessFile
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Regression coverage for the crash scenario that motivated [H2Recovery]: PoloCloud's process
 * dying mid-write left `database.mv.db` in a state where H2 throws
 * `MVStoreException` / error 90030 ("File is corrupted") on every future startup, and
 * `DatabaseConnectionFactory.connect` used to treat that as fatal.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class H2RecoveryTest {

    private val logger = LoggerFactory.getLogger(H2RecoveryTest::class.java)
    private lateinit var workDir: File

    @BeforeAll
    fun initTranslations() {
        // Real TranslationService - H2Recovery logs through it unconditionally, and it always
        // does a remote meta check even for cached packs, so this needs network access.
        TranslationService.init()
        TranslationService.preload("database")
    }

    @BeforeEach
    fun setUp() {
        workDir = File("h2-recovery/${System.nanoTime()}").apply { mkdirs() }
    }

    @AfterEach
    fun tearDown() {
        if (DatabaseAccessIsInitialized()) {
            DatabaseAccess.close()
        }
        workDir.deleteRecursively()
    }

    // DatabaseAccess.connection is a lateinit var with no "isInitialized" accessor exposed,
    // so close() would throw if connect() was never called in a given test - guard for that.
    private fun DatabaseAccessIsInitialized(): Boolean = try {
        DatabaseAccess.executor(); true
    } catch (_: Exception) {
        false
    }

    @Test
    fun `isCorruption detects wrapped H2 error code 90030`() {
        val cause = MVStoreException(ErrorCode.FILE_CORRUPTED_1, "File is corrupted")
        val wrapped = RuntimeException("pool init failed", SQLException("wrapper", "HY000", ErrorCode.FILE_CORRUPTED_1, cause))

        assertTrue(H2Recovery.isCorruption(wrapped))
    }

    @Test
    fun `isCorruption ignores unrelated failures`() {
        assertFalse(H2Recovery.isCorruption(IllegalStateException("disk full")))
        assertFalse(H2Recovery.isCorruption(SQLException("wrong user or password", "28000", ErrorCode.WRONG_USER_OR_PASSWORD)))
    }

    @Test
    fun `tryRecover is a no-op when there is nothing to recover from`() {
        val path = File(workDir, "database").path

        assertNull(H2Recovery.tryRecover(path, logger))
    }

    @Test
    fun `severely corrupted store falls back to an empty but connectable database`() {
        val dbPath = File(workDir, "database").path

        createDatabaseWithRows(dbPath, rows = 20)
        val originalFile = File("$dbPath.mv.db")
        assertTrue(originalFile.exists())

        corruptHeaders(originalFile)
        assertUnopenable(dbPath)

        DatabaseAccess.initialize(DatabaseCredentials.H2(dbPath))
        val connected = DatabaseAccess.connect()

        assertTrue(connected, "node must still be able to start after H2 corruption")

        val notice = DatabaseAccess.recoveryNotice()
        assertTrue(notice != null, "a recovery notice must be recorded so the operator can be warned")
        assertEquals(RecoverySeverity.CRITICAL, notice!!.severity)

        // The corrupt original must have been preserved, not discarded.
        val backups = workDir.listFiles { f -> f.name.contains(".corrupt-") }
        assertEquals(1, backups?.size, "exactly one backup of the corrupted file must remain on disk")

        // The database must be usable again, even though empty.
        DriverManager.getConnection("jdbc:h2:file:./$dbPath;DB_CLOSE_ON_EXIT=TRUE", "sa", "").use { conn ->
            conn.createStatement().execute("CREATE TABLE IF NOT EXISTS sanity_check (id INT PRIMARY KEY)")
        }
    }

    @Test
    fun `recoverFromCorruption rebuilds an already-open pool after corruption strikes mid-operation`() {
        // Distinct from the "corrupted before we ever connected" scenario above: here the pool
        // is already up and serving traffic (like a running node) when the file goes bad on disk.
        val dbPath = File(workDir, "database").path
        val credentials = DatabaseCredentials.H2(dbPath)
        val factory = SqlConnectionFactory(credentials)

        factory.connect(credentials)
        assertEquals(DatabaseState.CONNECTED, factory.state)
        assertNull(factory.recoveryNotice, "a clean connect must not carry a stale recovery notice")

        val setupConn = factory.dataSource!!.connection
        setupConn.createStatement().use { st ->
            st.execute("CREATE TABLE players (id INT PRIMARY KEY, name VARCHAR(255))")
            st.execute("INSERT INTO players VALUES (1, 'steve')")
            // DB_CLOSE_DELAY=-1 (see SqlConnectionFactory.connect) keeps the embedded engine
            // alive in-process even with zero open connections, so a subsequent "fresh" JDBC
            // connection would otherwise just re-attach to the same live, fully-cached engine
            // and never touch disk. SHUTDOWN forces it closed so the next connection attempt -
            // exactly like the one SqlExecutor.withConnection has to make once the pool needs a
            // new physical connection - genuinely reopens the file from disk. H2 then rejects any
            // further use of setupConn (including close()), which is expected here.
            st.execute("SHUTDOWN")
        }
        try {
            setupConn.close()
        } catch (_: Exception) {
        }

        corruptHeaders(File("$dbPath.mv.db"))

        // A fresh physical connection is exactly what SqlExecutor.withConnection ends up
        // triggering once the pool needs to (re)open one - same real exception type/path
        // it would catch, just obtained directly here for a deterministic test.
        val trigger = try {
            DriverManager.getConnection("jdbc:h2:file:./$dbPath;DB_CLOSE_ON_EXIT=TRUE", "sa", "").close()
            null
        } catch (ex: SQLException) {
            ex
        }
        assertTrue(trigger != null, "test setup must reproduce a genuine H2 corruption error")
        assertTrue(H2Recovery.isCorruption(trigger!!))

        val recovered = factory.recoverFromCorruption(trigger)

        assertTrue(recovered, "recoverFromCorruption must rebuild the pool instead of giving up")
        assertEquals(DatabaseState.CONNECTED, factory.state)
        assertEquals(RecoverySeverity.CRITICAL, factory.recoveryNotice?.severity)

        // The rebuilt pool must be genuinely usable, not just marked CONNECTED.
        factory.dataSource!!.connection.use { conn ->
            conn.createStatement().execute("CREATE TABLE IF NOT EXISTS sanity_check (id INT PRIMARY KEY)")
        }

        // A second call with an unrelated failure must be a plain no-op (not re-run recovery).
        assertFalse(factory.recoverFromCorruption(IllegalStateException("unrelated")))

        factory.close()
    }

    @Test
    fun `write delay is disabled for embedded H2 connections`() {
        val dbPath = File(workDir, "database").path

        DatabaseAccess.initialize(DatabaseCredentials.H2(dbPath))
        assertTrue(DatabaseAccess.connect())

        DriverManager.getConnection("jdbc:h2:file:./$dbPath;DB_CLOSE_ON_EXIT=TRUE", "sa", "").use { conn ->
            conn.createStatement()
                .executeQuery("SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME = 'WRITE_DELAY'")
                .use { rs ->
                    assertTrue(rs.next())
                    assertEquals("0", rs.getString(1))
                }
        }
    }

    @Test
    fun `checkpoint runs CHECKPOINT SYNC without throwing`() {
        val dbPath = File(workDir, "database").path

        DatabaseAccess.initialize(DatabaseCredentials.H2(dbPath))
        assertTrue(DatabaseAccess.connect())

        DatabaseAccess.checkpoint()
    }

    private fun createDatabaseWithRows(dbPath: String, rows: Int) {
        DriverManager.getConnection("jdbc:h2:file:./$dbPath;DB_CLOSE_ON_EXIT=TRUE", "sa", "").use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE players (id INT PRIMARY KEY, name VARCHAR(255))")
                repeat(rows) { i -> st.execute("INSERT INTO players VALUES ($i, 'player-$i')") }
            }
        }
    }

    /**
     * Smashes both redundant MVStore header blocks at the start of the file. Unlike scattering
     * random single-byte hits across the whole file (which - depending on file size/layout - can
     * land somewhere H2 tolerates or never reads back from its in-memory cache), this reliably
     * makes the store unopenable regardless of how much data is in it, matching the real crash
     * report this whole recovery path was built for ("File is corrupted ... unable to recover a
     * valid set of chunks").
     */
    private fun corruptHeaders(file: File) {
        RandomAccessFile(file, "rw").use { raf ->
            val headerSize = minOf(8192L, raf.length()).toInt()
            val garbage = ByteArray(headerSize)
            java.util.Random(1).nextBytes(garbage)
            raf.seek(0)
            raf.write(garbage)
        }
    }

    private fun assertUnopenable(dbPath: String) {
        try {
            DriverManager.getConnection("jdbc:h2:file:./$dbPath;DB_CLOSE_ON_EXIT=TRUE", "sa", "").close()
            throw AssertionError("corruption did not actually break the file - test setup is not reproducing the crash scenario")
        } catch (ex: SQLException) {
            assertTrue(H2Recovery.isCorruption(ex), "expected an H2 file-corruption error, got: $ex")
        }
    }
}
