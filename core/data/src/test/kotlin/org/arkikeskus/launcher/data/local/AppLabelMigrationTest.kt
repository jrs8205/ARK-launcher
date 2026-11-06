package org.arkikeskus.launcher.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.arkikeskus.launcher.data.di.MIGRATION_7_8
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Version 8 adds the app-label table beside the home layout. A phone's version-7 database — built
 * here from the exported schema, as a 1.0.2 install left it — must open on version 8 with the
 * migration alone: Room validates every table against the entities after it runs, so a CREATE that
 * differs from the entity's would throw here instead of on the first launch after the update.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLabelMigrationTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun aVersion7DatabaseMigratesTo8AndKeepsItsRows() {
        val file = File(context.cacheDir, "migration-test.db")
        file.delete()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val schema = JSONObject(File("schemas/org.arkikeskus.launcher.data.local.LauncherDatabase/7.json").readText())
            val entity = schema.getJSONObject("database").getJSONArray("entities").getJSONObject(0)
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            db.execSQL(entity.getJSONArray("indices").getJSONObject(0).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            db.execSQL(
                "INSERT INTO home_items (containerId, folderName, packageName, className, userSerial, shortcutId, page, cellX, cellY, spanX, spanY) " +
                    "VALUES (-1, NULL, 'com.mail', 'mail.Main', 0, NULL, 0, 1, 2, 1, 1)",
            )
            db.version = 7
        }

        val database = Room.databaseBuilder(context, LauncherDatabase::class.java, file.absolutePath)
            .addMigrations(MIGRATION_7_8)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                val rows = database.homeItemDao().getAll()
                assertThat(rows.map { it.key }).containsExactly("com.mail/mail.Main/0")

                database.appLabelDao().replaceAll(listOf(AppLabelEntity("com.mail/mail.Main/0", "Mail", "en-US")))
                assertThat(database.appLabelDao().getAll("en-US").map { it.label }).containsExactly("Mail")
            }
            assertThat(database.openHelper.readableDatabase.version).isEqualTo(8)
        } finally {
            database.close()
        }
    }
}
