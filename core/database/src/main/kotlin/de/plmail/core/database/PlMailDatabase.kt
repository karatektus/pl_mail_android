package de.plmail.core.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The local cache.
 *
 * **Migrated from version 11 on.** Up to version 11, the one v0.4.1 shipped, every bump dropped the
 * database and synced again. Every row here is reconstructible from the server (see `Entities.kt`),
 * so that was safe, and it saved writing migrations. It was not free: the drop took every
 * downloaded body with it, every list was paged again from the top, and to the person updating the
 * app it looked like their mail reloading from nothing — on every release that touched the schema.
 *
 * So from 11 each bump comes with a migration, and the destructive fallback is kept only for the
 * versions before it, which have no exported path forward worth writing, and for a downgrade, which
 * only a development build does. A bump without a migration now fails to open rather than quietly
 * wiping the cache; `MigrationFromV11Test` runs the real migration against the exported schema.
 *
 * Most bumps are columns, and Room writes those itself from the exported schemas: an
 * [AutoMigration] per version, a `@ColumnInfo(defaultValue)` on any column that is not null. A
 * column whose correct value is not its default, as 12's are, says so in its spec.
 *
 * Version 12 is the conversation's deleted messages — `ThreadEntity.binnedCount` and
 * `binnedReceivedAt` — and the reply headers the composer reads from the cache: the five
 * `EmailBodyEntity` columns from `hasReplyHeaders` to `references`. See [FromV11].
 *
 * The exported schemas are checked in, and must be: they are what every auto-migration is written
 * from, so an entity and its exported description drifting apart would produce a migration to a
 * schema that was never true.
 */
@Database(
    entities =
        [
            AccountEntity::class,
            MailboxEntity::class,
            ThreadEntity::class,
            EmailEntity::class,
            EmailBodyEntity::class,
            AttachmentEntity::class,
            FeedEntryEntity::class,
            FeedCursorEntity::class,
            IdentityEntity::class,
            CalendarEntity::class,
            CalendarEventEntity::class,
            CalendarOccurrenceEntity::class,
            InboxTabEntity::class,
        ],
    version = 12,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 11, to = 12, spec = PlMailDatabase.FromV11::class)],
)
abstract class PlMailDatabase : RoomDatabase() {

    abstract fun accounts(): AccountDao

    abstract fun mailboxes(): MailboxDao

    abstract fun threads(): ThreadDao

    abstract fun emails(): EmailDao

    abstract fun feed(): FeedDao

    abstract fun identities(): IdentityDao

    abstract fun calendars(): CalendarDao

    abstract fun calendarEvents(): CalendarEventDao

    abstract fun inboxTabs(): InboxTabDao

    companion object {
        const val NAME = "plmail.db"

        fun create(context: Context): PlMailDatabase =
            Room.databaseBuilder(context, PlMailDatabase::class.java, NAME)
                // The versions from before migrations were written, and a
                // downgrade. Safe because nothing here is irreplaceable -- the
                // credential lives behind the Keystore and the rest re-syncs --
                // but no longer the answer to every bump: see the class.
                .fallbackToDestructiveMigrationFrom(dropAllTables = true, *BEFORE_MIGRATIONS)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()

        /** Every version before the first one migrated from. */
        private val BEFORE_MIGRATIONS = (1..10).toList().toIntArray()
    }

    /**
     * From v0.4.1's schema: seven new columns, and the cursors cleared.
     *
     * The columns arrive at their defaults, and two of those are wrong for rows already here: a
     * conversation's deleted messages are counted as none, so it counts them outside Trash until
     * something rewrites it. Clearing every account's sync cursors makes the next sync page the
     * lists again, which rewrites every conversation on them with the counts the page brings — what
     * the drop used to do, without throwing away the bodies already downloaded.
     */
    class FromV11 : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE accounts SET emailState = NULL, threadState = NULL")
        }
    }
}
