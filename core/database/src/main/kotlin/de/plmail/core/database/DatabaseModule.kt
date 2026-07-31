package de.plmail.core.database

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * One database for the process.
     *
     * Room tolerates a second instance over the same file and then quietly gives it its own
     * in-memory invalidation tracker, so writes through one stop waking observers on the other — a
     * list that never updates, with nothing in the logs.
     */
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): PlMailDatabase =
        PlMailDatabase.create(context)

    @Provides fun accounts(database: PlMailDatabase): AccountDao = database.accounts()

    @Provides fun mailboxes(database: PlMailDatabase): MailboxDao = database.mailboxes()

    @Provides fun threads(database: PlMailDatabase): ThreadDao = database.threads()

    @Provides fun emails(database: PlMailDatabase): EmailDao = database.emails()

    @Provides fun feed(database: PlMailDatabase): FeedDao = database.feed()

    @Provides fun identities(database: PlMailDatabase): IdentityDao = database.identities()
}
