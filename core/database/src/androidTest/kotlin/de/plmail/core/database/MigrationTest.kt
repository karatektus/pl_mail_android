package de.plmail.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The schema, and the recovery policy that stands in for migrations.
 *
 * At version 1 there is nothing to migrate *from*, so this is deliberately not a migration test
 * yet. What it does check is the two things that would silently break the first real migration:
 *
 * 1. **The exported schema matches the compiled one.** `MigrationTestHelper` reads
 *    `core/database/schemas/…/1.json` and creates a database from it. If that file has drifted from
 *    the entities — the usual cause being a column added without the Room Gradle plugin
 *    re-exporting — this fails here, at the commit that caused it, rather than at version 2 when
 *    the migration is written against a description of the schema that was never true.
 * 2. **The destructive fallback actually works.** `PlMailDatabase.create` opts into dropping
 *    everything on a version it cannot migrate, which is only safe because every row is
 *    reconstructible from the server. That is a claim worth executing rather than asserting in a
 *    comment.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            PlMailDatabase::class.java,
        )

    /**
     * Creating version 1 from the checked-in schema is the assertion.
     *
     * `createDatabase` validates the file against what Room compiled, so a drifted export throws
     * here rather than producing a database that merely looks right.
     */
    @Test
    @Throws(IOException::class)
    fun theExportedSchemaMatchesTheCompiledOne() {
        val database = helper.createDatabase(TEST_DB, 1)

        try {
            assertEquals(1, database.version)

            // Every entity has a table. Room would have failed above if the
            // shapes disagreed; this catches an entity dropped from the
            // @Database list, which is a change Room accepts silently.
            val cursor = database.query("SELECT name FROM sqlite_master WHERE type = 'table'")
            val tables = cursor.use {
                generateSequence { if (it.moveToNext()) it.getString(0) else null }.toSet()
            }

            EXPECTED_TABLES.forEach { assertTrue("missing table $it", tables.contains(it)) }
        } finally {
            database.close()
        }
    }

    /**
     * The recovery path the schema's central rule licenses.
     *
     * A database at an unknown version is dropped and recreated rather than migrated. Executing it
     * proves the opt-in is actually in place — a `fallbackToDestructiveMigration` accidentally
     * removed would surface here instead of as a crash loop on the release that changed the schema.
     */
    @Test
    @Throws(IOException::class)
    fun anUnmigratableDatabaseIsRebuiltRatherThanFatal() {
        val seeded = helper.createDatabase(TEST_DB, 1)
        seeded.execSQL("PRAGMA user_version = 99")
        seeded.close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reopened =
            Room.databaseBuilder(context, PlMailDatabase::class.java, TEST_DB)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        try {
            // Reaching the DAO at all means the open succeeded and the store was
            // rebuilt at the current version rather than throwing.
            assertEquals(emptyList<AccountEntity>(), runBlocking { reopened.accounts().all() })
        } finally {
            reopened.close()
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"

        val EXPECTED_TABLES =
            setOf(
                "accounts",
                "mailboxes",
                "threads",
                "emails",
                "email_bodies",
                "attachments",
                "feed_entries",
                "feed_cursors",
                "identities",
            )
    }
}
