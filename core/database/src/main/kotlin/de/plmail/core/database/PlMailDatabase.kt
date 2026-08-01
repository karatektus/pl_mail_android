package de.plmail.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The local cache.
 *
 * **Version 2**, and the way it got there is the point. `ThreadEntity` grew `labelKeys`, and rather
 * than writing a migration this bump deliberately falls through to dropping the database and
 * syncing again — which is exactly what the schema's central constraint was for. Every row here is
 * reconstructible from the server (see `Entities.kt`), so the cost of the drop is one page of mail
 * per list the user opens, and the alternative is the first hand-written migration in a schema
 * designed never to need one, plus a backfill that would have to reconstruct labels by string
 * matching `mailboxIds` against `mailboxes` in SQL.
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
        ],
    version = 2,
    exportSchema = true,
)
abstract class PlMailDatabase : RoomDatabase() {

    abstract fun accounts(): AccountDao

    abstract fun mailboxes(): MailboxDao

    abstract fun threads(): ThreadDao

    abstract fun emails(): EmailDao

    abstract fun feed(): FeedDao

    abstract fun identities(): IdentityDao

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
