package de.plmail.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The local cache.
 *
 * Version 1, and the migration policy is the interesting part: because every row here is
 * reconstructible from the server (see `Entities.kt`), a failed migration is recoverable by
 * deleting the file and syncing again. That is why [create] enables destructive fallback — not
 * carelessness, but the one place where the schema's central constraint pays for itself.
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
    version = 1,
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
