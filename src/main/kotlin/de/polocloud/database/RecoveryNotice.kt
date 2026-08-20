package de.polocloud.database

/**
 * Non-fatal diagnostic surfaced when [DatabaseConnectionFactory.connect] had to take a
 * corrective action instead of connecting normally - e.g. auto-recovering a corrupted
 * embedded H2 store.
 *
 * `null` after a clean connect. Consumers should check [DatabaseConnectionFactory.recoveryNotice]
 * (or `DatabaseAccess.recoveryNotice()`) after every [DatabaseConnectionFactory.connect] call and
 * surface it prominently (startup banner, alert) on top of - not instead of - the regular log
 * output, since log lines are easy to miss during a boot sequence.
 */
data class RecoveryNotice(
    val severity: RecoverySeverity,
    val message: String
)

enum class RecoverySeverity {
    /** Recovered with no reason to believe data was lost. */
    INFO,

    /** Recovered, but some data may be missing or the store needed intervention. */
    WARNING,

    /** Recovery could not preserve the existing data - started from an empty database. */
    CRITICAL
}
