package de.plmail.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The local cache.
 *
 * **Version 4**, and the way it got there is the point. Version 2 gave `ThreadEntity` its
 * `labelKeys`; version 3 gave `MailboxEntity` a `color` and `ThreadEntity` a `category`; version 4
 * adds the three calendar tables. Rather than writing a migration each bump deliberately falls
 * through to dropping the database and syncing again — which is exactly what the schema's central
 * constraint was for. Every row here is reconstructible from the server (see `Entities.kt`), so the
 * cost of the drop is one page of mail per list the user opens, and the alternative is the first
 * hand-written migration in a schema designed never to need one, plus a backfill that would have to
 * reconstruct labels by string matching `mailboxIds` against `mailboxes` in SQL.
 *
 * Version 4 is a pure addition and Room could have been given an empty migration for it — three
 * `CREATE TABLE`s and nothing to move. It is a destructive bump anyway, because the value of the
 * policy is that there is exactly one of them: a schema where some bumps preserve data and some do
 * not is one where the next person has to work out which kind theirs is, and the calendar tables
 * are the least costly rows in the store to lose. The occurrence table in particular is derived
 * from a query the client can simply re-run.
 *
 * **Two columns, one bump.** They arrived in the same session and each is a wipe-and-resync on its
 * own; shipping them as versions 3 and 4 would have cost a second full re-sync for nothing. Anyone
 * adding a third column before this ships should join it to this one rather than adding version 4.
 *
 * The exported schemas are still checked in and `MigrationTest` still validates them, because that
 * is what catches an entity and its exported description drifting apart — a drift which, on the day
 * somebody *does* need a real migration, would be written against a schema that was never true.
 *
 * If a future column ever holds something the server does not know about, this decision becomes
 * wrong and both have to change together.
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
        ],
    version = 4,
    exportSchema = true,
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

    companion object {
        const val NAME = "plmail.db"

        fun create(context: Context): PlMailDatabase =
            Room.databaseBuilder(context, PlMailDatabase::class.java, NAME)
                // Safe precisely because nothing here is irreplaceable. The
                // credential lives behind the Keystore and the rest re-syncs.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
