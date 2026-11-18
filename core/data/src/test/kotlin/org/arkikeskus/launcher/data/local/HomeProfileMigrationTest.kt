package org.arkikeskus.launcher.data.local

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.arkikeskus.launcher.data.di.MIGRATION_8_9
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Version 9 gives every home layout row its home profile. A phone's version-8 database, built here from the
 * exported schema as a 1.2.1 install left it, must open on version 9 with the migration alone, every row in
 * the first profile: Room checks the table against the entity after the migration runs, so a statement that
 * differs from the entity's would throw here instead of on the first start after the update.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeProfileMigrationTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun aVersion8DatabaseMigratesTo9AndKeepsItsRowsInTheFirstProfile() {
        val file = File(context.cacheDir, "profile-migration-test.db")
        file.delete()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val schema = JSONObject(File("schemas/org.arkikeskus.launcher.data.local.LauncherDatabase/8.json").readText())
            val entities = schema.getJSONObject("database").getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            db.execSQL(
                "INSERT INTO home_items (containerId, folderName, packageName, className, userSerial, shortcutId, page, cellX, cellY, spanX, spanY) " +
                    "VALUES (-1, NULL, 'com.mail', 'mail.Main', 0, NULL, 0, 1, 2, 1, 1)",
            )
            db.execSQL(
                "INSERT INTO home_items (containerId, folderName, packageName, className, userSerial, shortcutId, page, cellX, cellY, spanX, spanY, appWidgetId, widgetProvider) " +
                    "VALUES (-1, NULL, 'com.clock', '', 0, NULL, 0, 0, 0, 2, 1, 41, 'com.clock/.Widget')",
            )
            db.execSQL(
                "INSERT INTO home_items (containerId, folderName, packageName, className, userSerial, shortcutId, page, cellX, cellY, spanX, spanY) " +
                    "VALUES (-2, NULL, 'com.phone', 'phone.Main', 0, NULL, 0, 0, 0, 1, 1)",
            )
            db.version = 8
        }

        val database = Room.databaseBuilder(context, LauncherDatabase::class.java, file.absolutePath)
            .addMigrations(MIGRATION_8_9)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                val rows = database.homeItemRoomDao().getAllOfEveryProfile()
                assertThat(rows.map { it.packageName }).containsExactly("com.mail", "com.clock", "com.phone")
                assertThat(rows.map { it.homeProfileId }.toSet()).containsExactly(1L)
                assertThat(rows.single { it.packageName == "com.clock" }.appWidgetId).isEqualTo(41)
                // The launcher as it was: the first profile sees every row.
                assertThat(database.homeItemDao().getAll()).hasSize(3)

                // One item per cell within a profile; the same cell is another profile's own.
                val mail = rows.single { it.packageName == "com.mail" }
                database.homeItemRoomDao().insert(mail.copy(id = 0, homeProfileId = 2))
                assertThrows(SQLiteConstraintException::class.java) {
                    runBlocking { database.homeItemRoomDao().insert(mail.copy(id = 0)) }
                }
            }
            assertThat(database.openHelper.readableDatabase.version).isEqualTo(9)
        } finally {
            database.close()
        }
    }
}
