package de.polocloud.database.sql

import de.polocloud.database.DatabaseAccess
import de.polocloud.database.DatabaseCredentials
import de.polocloud.database.DatabaseKey
import de.polocloud.database.EntryIdentifier
import de.polocloud.database.EntryRef
import de.polocloud.database.RepositoryName
import de.polocloud.database.exeption.FactoryNotPresentException
import de.polocloud.database.filtering.And
import de.polocloud.database.filtering.Eq
import de.polocloud.database.filtering.GreaterThan
import de.polocloud.database.filtering.In
import de.polocloud.database.filtering.Like
import de.polocloud.i18n.api.TranslationService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.sql.DriverManager
import java.util.UUID
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * End-to-end coverage of [SqlExecutor]'s CRUD/reflection logic (entity mapping, table
 * auto-creation, filter translation actually reaching the database, @EntryRef foreign keys,
 * @RepositoryName table naming) against a real embedded H2 instance - no mocking, since H2 is
 * fast, deterministic and needs no external server, and every SQL backend this module supports
 * shares this exact same [SqlExecutor] implementation.
 */
@OptIn(ExperimentalTime::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqlExecutorTest {

    enum class Role { LEADER, MEMBER }

    data class Player(
        @EntryIdentifier
        val id: Int,
        val name: String,
        val role: Role,
        val level: Int
    )

    data class Session(
        @EntryIdentifier
        val id: UUID,
        val label: String,
        val startedAt: Instant
    )

    data class Team(
        @EntryIdentifier
        val id: Int,
        val teamName: String
    )

    @RepositoryName("club_members")
    data class ClubMember(
        @EntryIdentifier
        val id: Int,
        val memberName: String,
        @EntryRef(Team::class)
        val teamId: Int
    )

    private lateinit var workDir: File

    @BeforeAll
    fun initTranslations() {
        TranslationService.init()
        TranslationService.preload("database")
    }

    @BeforeEach
    fun setUp() {
        workDir = File("sql-executor/${System.nanoTime()}").apply { mkdirs() }
        DatabaseAccess.initialize(DatabaseCredentials.H2(File(workDir, "database").path))
        assertTrue(DatabaseAccess.connect())
    }

    @AfterEach
    fun tearDown() {
        DatabaseAccess.close()
        workDir.deleteRecursively()
    }

    private val playerKey = DatabaseKey(Player::class)

    @Test
    fun `save creates the table on first use and the entity round-trips through findById`() {
        val executor = DatabaseAccess.executor()
        val steve = Player(1, "steve", Role.LEADER, 42)

        executor.save(playerKey, steve)

        assertEquals(steve, executor.findById(playerKey, 1))
    }

    @Test
    fun `save on an existing id updates in place instead of inserting a duplicate`() {
        val executor = DatabaseAccess.executor()
        executor.save(playerKey, Player(1, "steve", Role.LEADER, 1))

        executor.save(playerKey, Player(1, "steve", Role.LEADER, 99))

        assertEquals(99, executor.findById(playerKey, 1)?.level)
        assertEquals(1L, executor.count(playerKey))
    }

    @Test
    fun `findAll returns every row mapped back to the entity type`() {
        val executor = DatabaseAccess.executor()
        executor.save(playerKey, Player(1, "steve", Role.LEADER, 10))
        executor.save(playerKey, Player(2, "alex", Role.MEMBER, 5))

        assertEquals(
            setOf(Player(1, "steve", Role.LEADER, 10), Player(2, "alex", Role.MEMBER, 5)),
            executor.findAll(playerKey).toSet()
        )
    }

    @Test
    fun `findById returns null for a missing row`() {
        assertNull(DatabaseAccess.executor().findById(playerKey, 999))
    }

    @Test
    fun `find with a single filter is translated and applied by the database`() {
        val executor = DatabaseAccess.executor()
        executor.save(playerKey, Player(1, "steve", Role.LEADER, 10))
        executor.save(playerKey, Player(2, "alex", Role.MEMBER, 5))

        assertEquals(listOf(Player(1, "steve", Role.LEADER, 10)), executor.find(playerKey, Eq("name", "steve")))
    }

    @Test
    fun `find combines multiple filters and supports IN and LIKE`() {
        val executor = DatabaseAccess.executor()
        executor.save(playerKey, Player(1, "steve", Role.LEADER, 10))
        executor.save(playerKey, Player(2, "alex", Role.MEMBER, 20))
        executor.save(playerKey, Player(3, "steven", Role.MEMBER, 30))

        val result = executor.find(playerKey, Like("name", "%stev%"), GreaterThan("level", 15))
        assertEquals(listOf(Player(3, "steven", Role.MEMBER, 30)), result)

        val inResult = executor.find(playerKey, In("id", listOf(1, 2)))
        assertEquals(setOf(1, 2), inResult.map { it.id }.toSet())
    }

    @Test
    fun `find with no filters behaves like findAll`() {
        val executor = DatabaseAccess.executor()
        executor.save(playerKey, Player(1, "steve", Role.LEADER, 10))

        assertEquals(executor.findAll(playerKey), executor.find(playerKey))
    }

    @Test
    fun `count reflects filtered and unfiltered row counts`() {
        val executor = DatabaseAccess.executor()
        executor.save(playerKey, Player(1, "steve", Role.LEADER, 10))
        executor.save(playerKey, Player(2, "alex", Role.MEMBER, 20))

        assertEquals(2L, executor.count(playerKey))
        assertEquals(1L, executor.count(playerKey, Eq("role", "LEADER")))
    }

    @Test
    fun `exists reflects whether an entity with the same identifier is present`() {
        val executor = DatabaseAccess.executor()
        val steve = Player(1, "steve", Role.LEADER, 10)

        assertFalse(executor.exists(playerKey, steve))
        executor.save(playerKey, steve)
        assertTrue(executor.exists(playerKey, steve))
    }

    @Test
    fun `delete removes exactly the targeted row`() {
        val executor = DatabaseAccess.executor()
        executor.save(playerKey, Player(1, "steve", Role.LEADER, 10))
        executor.save(playerKey, Player(2, "alex", Role.MEMBER, 20))

        executor.delete(playerKey, Player(1, "steve", Role.LEADER, 10))

        assertNull(executor.findById(playerKey, 1))
        assertEquals(1L, executor.count(playerKey))
    }

    @Test
    fun `destroy drops the table and a later save transparently recreates it`() {
        val executor = DatabaseAccess.executor()
        executor.save(playerKey, Player(1, "steve", Role.LEADER, 10))

        executor.destroy(playerKey)
        assertTrue(executor.findAll(playerKey).isEmpty())

        executor.save(playerKey, Player(2, "alex", Role.MEMBER, 5))
        assertEquals(listOf(Player(2, "alex", Role.MEMBER, 5)), executor.findAll(playerKey))
    }

    @Test
    fun `UUID and Instant fields round-trip through the database`() {
        val executor = DatabaseAccess.executor()
        val key = DatabaseKey(Session::class)
        val id = UUID.randomUUID()
        val startedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val session = Session(id, "morning-run", startedAt)

        executor.save(key, session)

        assertEquals(session, executor.findById(key, id))
    }

    @Test
    fun `RepositoryName controls the actual table name, not the class's simple name`() {
        val executor = DatabaseAccess.executor()
        executor.save(DatabaseKey(Team::class), Team(1, "red"))
        executor.save(DatabaseKey(ClubMember::class), ClubMember(1, "steve", teamId = 1))

        DriverManager.getConnection(
            "jdbc:h2:file:./${File(workDir, "database").path};DB_CLOSE_ON_EXIT=TRUE",
            "sa",
            ""
        ).use { conn ->
            val tables = conn.metaData.getTables(null, "PUBLIC", null, arrayOf("TABLE"))
            // H2 uppercases unquoted identifiers internally, hence the uppercase comparison here -
            // querying still works case-insensitively via the lowercase name from @RepositoryName.
            val names = generateSequence { if (tables.next()) tables.getString("TABLE_NAME") else null }.toSet()

            assertTrue(names.contains("CLUB_MEMBERS"), "expected the @RepositoryName-provided table name, got: $names")
            assertFalse(names.contains("CLUBMEMBER"), "must not fall back to the class's simple name")
        }
    }

    @Test
    fun `EntryRef creates the referenced table first and the foreign key holds`() {
        val executor = DatabaseAccess.executor()
        val memberKey = DatabaseKey(ClubMember::class)
        executor.save(DatabaseKey(Team::class), Team(1, "red"))
        executor.save(memberKey, ClubMember(1, "steve", teamId = 1))

        assertEquals(ClubMember(1, "steve", teamId = 1), executor.findById(memberKey, 1))

        // SqlExecutor.update() logs and swallows SQL errors rather than rethrowing them, so a
        // foreign key violation surfaces as "the row was silently not inserted" - not an
        // exception. This still proves the @EntryRef -> REFERENCES constraint is genuinely
        // enforced by the database, not just present in the generated DDL.
        executor.save(memberKey, ClubMember(2, "alex", teamId = 999))
        assertNull(executor.findById(memberKey, 2))
    }

    @Test
    fun `operations throw FactoryNotPresentException once the connection is closed`() {
        val executor = DatabaseAccess.executor()
        DatabaseAccess.close()

        assertThrows(FactoryNotPresentException::class.java) {
            executor.save(playerKey, Player(1, "steve", Role.LEADER, 10))
        }
    }

    @Test
    fun `nested And filter composition reaches the database correctly`() {
        val executor = DatabaseAccess.executor()
        executor.save(playerKey, Player(1, "steve", Role.LEADER, 10))
        executor.save(playerKey, Player(2, "steve", Role.MEMBER, 99))

        val result = executor.find(playerKey, And(listOf(Eq("name", "steve"), Eq("role", "LEADER"))))

        assertEquals(listOf(Player(1, "steve", Role.LEADER, 10)), result)
    }
}
