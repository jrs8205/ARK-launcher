package org.arkikeskus.launcher.data.backup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.data.local.LauncherDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test

class BackupDaoTest {
    private lateinit var db: LauncherDatabase

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), LauncherDatabase::class.java,
        ).build()
    }
    @After fun teardown() = db.close()

    @Test fun replaceLayout_wipes_then_inserts_preserving_ids() = runTest {
        val dao = db.homeItemDao()
        dao.insert(HomeItemEntity(packageName = "old", className = "O", page = 0, cellX = 0, cellY = 0))
        dao.replaceLayout(
            listOf(
                HomeItemEntity(id = 5, folderName = "F", page = 0, cellX = 0, cellY = 0),
                HomeItemEntity(id = 6, containerId = 5, packageName = "x", className = "X", page = 0, cellX = 0, cellY = 0),
            ),
        )
        val all = dao.getAllOnce()
        assertThat(all.map { it.id }).containsExactly(5L, 6L)
        assertThat(all.first { it.id == 6L }.containerId).isEqualTo(5L) // child links to folder id
    }
}
