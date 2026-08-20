package de.polocloud.database.sql

import de.polocloud.database.RecoveryNotice
import de.polocloud.database.RecoverySeverity
import de.polocloud.i18n.api.TranslationService
import de.polocloud.i18n.api.trError
import de.polocloud.i18n.api.trWarn
import org.h2.api.ErrorCode
import org.h2.mvstore.MVStoreException
import org.h2.mvstore.MVStoreTool
import org.h2.tools.Recover
import org.h2.tools.RunScript
import org.slf4j.Logger
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant

/**
 * Best-effort recovery for a corrupted embedded H2 (MVStore) database file.
 *
 * The original `<path>.mv.db` is renamed aside as `<path>.mv.db.corrupt-<timestamp>` before
 * anything else is attempted, so a failed recovery attempt can never destroy data that could
 * still have been recovered manually from that backup. From there, in order:
 *  1. Repair the store in place ([MVStoreTool.repair]).
 *  2. Salvage whatever is still readable via [Recover] and replay it into a fresh store.
 *  3. Give up and continue with an empty database, leaving the backup (and any salvage
 *     artifacts) in place for manual inspection.
 *
 * Manual inspection: PoloCloud's embedded H2 always uses the hardcoded credentials `sa` / ""
 * (see [SqlConnectionFactory.connect]). Any manual H2 CLI tool run against a `.corrupt-*` backup
 * (`org.h2.tools.Shell`, `org.h2.tools.Console`, `RunScript`, ...) needs `-user sa -password ""`
 * explicitly, otherwise it fails with "Wrong user name or password" - the file-level tools used
 * here ([MVStoreTool], [Recover]) don't need credentials since they never go through a JDBC login.
 */
object H2Recovery {

    sealed interface Outcome {
        val dbFile: File
        val backup: File

        /** The store was rolled back to its last internally-consistent version in place. */
        data class Repaired(override val dbFile: File, override val backup: File) : Outcome

        /** The store was rebuilt from scratch and re-populated from whatever [Recover] could salvage. */
        data class PartiallyRecovered(override val dbFile: File, override val backup: File, val script: File) : Outcome

        /** Nothing could be salvaged - continuing with a fresh, empty database. */
        data class ResetToEmpty(override val dbFile: File, override val backup: File) : Outcome
    }

    /**
     * @return the recovery [Outcome] once `<path>.mv.db` is in a state [SqlConnectionFactory] can
     * open again (repaired, partially recovered, or reset to empty). `null` if there was nothing
     * to recover from (no file present), meaning the original failure wasn't caused by a corrupt file.
     */
    fun tryRecover(path: String, logger: Logger): Outcome? {
        val dbFile = File("./$path.mv.db")
        if (!dbFile.exists()) {
            return null
        }

        val backupFile = File(dbFile.parentFile ?: File("."), "${dbFile.name}.corrupt-${Instant.now().toEpochMilli()}")

        logger.trError(
            "database",
            "database.h2.corruption.detected",
            "path" to dbFile.path,
            "backup" to backupFile.path
        )

        Files.move(dbFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

        if (attempt(path, dbFile) { repair(backupFile, dbFile) }) {
            logger.trWarn("database", "database.h2.recovery.repaired", "path" to dbFile.path, "backup" to backupFile.path)
            return Outcome.Repaired(dbFile, backupFile)
        }
        logger.trWarn("database", "database.h2.recovery.repair_failed", "path" to dbFile.path)

        val dbDir = dbFile.parentFile ?: File(".")
        val dbName = dbFile.name.removeSuffix(".mv.db")
        val scriptFile = File(dbDir, "$dbName.h2.sql")
        if (attempt(path, dbFile) { recoverScript(path, dbDir, dbName, backupFile, dbFile) }) {
            if (hasUserTables(path)) {
                logger.trWarn(
                    "database",
                    "database.h2.recovery.partial",
                    "path" to dbFile.path,
                    "backup" to backupFile.path,
                    "script" to scriptFile.path
                )
                return Outcome.PartiallyRecovered(dbFile, backupFile, scriptFile)
            }
            // Nothing usable could be salvaged from the store - equivalent to an empty database.
        } else {
            logger.trWarn("database", "database.h2.recovery.script_failed", "path" to dbFile.path)
        }

        dbFile.delete()
        logger.trError("database", "database.h2.recovery.reset_to_empty", "path" to dbFile.path, "backup" to backupFile.path)
        return Outcome.ResetToEmpty(dbFile, backupFile)
    }

    /**
     * Detects whether [ex] (or one of its causes) is an H2 "file corrupted" failure
     * (error code 90030), as opposed to e.g. a permission or connectivity issue.
     */
    fun isCorruption(ex: Throwable): Boolean {
        var current: Throwable? = ex
        var depth = 0
        while (current != null && depth < 20) {
            if (current is MVStoreException && current.errorCode == ErrorCode.FILE_CORRUPTED_1) return true
            if (current is SQLException && current.errorCode == ErrorCode.FILE_CORRUPTED_1) return true
            current = current.cause
            depth++
        }
        return false
    }

    /**
     * Builds the operator-facing [RecoveryNotice] for this outcome, reusing the same
     * translation keys already logged by [tryRecover].
     */
    fun toNotice(outcome: Outcome): RecoveryNotice = when (outcome) {
        is Outcome.Repaired -> RecoveryNotice(
            RecoverySeverity.WARNING,
            TranslationService.tr("database", "database.h2.recovery.repaired", "path" to outcome.dbFile.path, "backup" to outcome.backup.path)
        )

        is Outcome.PartiallyRecovered -> RecoveryNotice(
            RecoverySeverity.WARNING,
            TranslationService.tr(
                "database",
                "database.h2.recovery.partial",
                "path" to outcome.dbFile.path,
                "backup" to outcome.backup.path,
                "script" to outcome.script.path
            )
        )

        is Outcome.ResetToEmpty -> RecoveryNotice(
            RecoverySeverity.CRITICAL,
            TranslationService.tr("database", "database.h2.recovery.reset_to_empty", "path" to outcome.dbFile.path, "backup" to outcome.backup.path)
        )
    }

    private fun attempt(path: String, dbFile: File, action: () -> Unit): Boolean {
        return try {
            action()
            canOpen(path)
        } catch (_: Exception) {
            dbFile.delete()
            false
        }
    }

    private fun repair(backupFile: File, dbFile: File) {
        Files.copy(backupFile.toPath(), dbFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        MVStoreTool.repair(dbFile.path)
        File(dbFile.path + ".back").delete()
        File(dbFile.path + ".temp").delete()
    }

    private fun recoverScript(path: String, dbDir: File, dbName: String, backupFile: File, dbFile: File) {
        Files.copy(backupFile.toPath(), dbFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        Recover.execute(dbDir.path, dbName)
        dbFile.delete()

        val scriptFile = File(dbDir, "$dbName.h2.sql")
        RunScript.execute("jdbc:h2:file:./$path", "sa", "", scriptFile.path, StandardCharsets.UTF_8, true)
    }

    private fun canOpen(path: String): Boolean {
        return try {
            DriverManager.getConnection("jdbc:h2:file:./$path;DB_CLOSE_ON_EXIT=TRUE", "sa", "").close()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun hasUserTables(path: String): Boolean {
        return try {
            DriverManager.getConnection("jdbc:h2:file:./$path;DB_CLOSE_ON_EXIT=TRUE", "sa", "").use { conn ->
                conn.metaData.getTables(null, "PUBLIC", null, arrayOf("TABLE")).use { rs -> rs.next() }
            }
        } catch (_: Exception) {
            false
        }
    }
}
