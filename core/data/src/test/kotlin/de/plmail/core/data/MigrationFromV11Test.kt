package de.plmail.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import de.plmail.core.database.PlMailDatabase
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Updating from v0.4.1 keeps the cache.
 *
 * Every schema bump up to that release dropped the database, and with it every downloaded body: to
 * somebody updating the app, their mail reloading from nothing. This builds a version 11 database
 * from its exported schema, as v0.4.1 left it on a phone, and opens it the way the app does.
 */
@RunWith(RobolectricTestRunner::class)
// sdk = 36 for the reason FeedProjectionTest gives.
@Config(sdk = [36])
class MigrationFromV11Test {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var opened: PlMailDatabase? = null

    @After
    fun close() {
        opened?.close()
        context.deleteDatabase(PlMailDatabase.NAME)
    }

    @Test
    fun `the bodies already downloaded survive the update`() = runTest {
        seedVersion11 {
            execSQL("INSERT INTO email_bodies (uid, textBody, fetchedAt) VALUES ('e1', 'Hello', 1)")
        }

        val database = PlMailDatabase.create(context).also { opened = it }

        val body = database.emails().body("e1")
        assertEquals("Hello", body?.textBody)
        assertEquals(false, body?.hasReplyHeaders)
    }

    /** The new counts are wrong at their defaults, so the lists are paged again to rewrite them. */
    @Test
    fun `every account is paged again after the update`() = runTest {
        seedVersion11 {
            execSQL(
                """
                INSERT INTO accounts (uid, serverId, accountId, name, isPersonal, isReadOnly,
                  sortIndex, wantsInboxTabs, emailState, threadState)
                VALUES ('a1', 's', '13', 'Me', 1, 0, 0, 0, 'e5', 't5')
                """
            )
        }

        val database = PlMailDatabase.create(context).also { opened = it }

        val account = database.accounts().byUid("a1")
        assertEquals("Me", account?.name)
        assertNull(account?.emailState)
        assertNull(account?.threadState)
    }

    /** The database v0.4.1 would have left, built from the schema it was compiled against. */
    private fun seedVersion11(rows: SQLiteDatabase.() -> Unit) {
        val schema =
            Json.parseToJsonElement(File(SCHEMAS, "11.json").readText())
                .jsonObject
                .getValue("database")
        val path = context.getDatabasePath(PlMailDatabase.NAME).apply { parentFile?.mkdirs() }

        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            schema.jsonObject.getValue("entities").jsonArray.forEach { entity ->
                val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
                fun sql(statement: String) = statement.replace("\${TABLE_NAME}", table)

                db.execSQL(sql(entity.jsonObject.getValue("createSql").jsonPrimitive.content))
                entity.jsonObject["indices"]?.jsonArray?.forEach { index ->
                    db.execSQL(sql(index.jsonObject.getValue("createSql").jsonPrimitive.content))
                }
            }
            schema.jsonObject.getValue("setupQueries").jsonArray.forEach {
                db.execSQL(it.jsonPrimitive.content)
            }
            db.rows()
            db.version = 11
        }
    }

    private companion object {
        /** The exported schemas, from this module's directory, where the test runs. */
        val SCHEMAS = File("../database/schemas/de.plmail.core.database.PlMailDatabase")
    }
}
