package ir.vmessenger.core.common.database

/**
 * Room schema version of the on-device database, bumped by every migration.
 *
 * It lives here rather than as a literal in `@Database` because the About screen shows it, and a
 * feature module must not pull in Room to read one integer. `VMessengerDatabase` annotates itself
 * with this constant, so the number on screen is the number the database is actually at.
 */
object DatabaseSchema {
    const val VERSION = 21
}
