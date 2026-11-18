package org.arkikeskus.launcher.data.local

import android.os.Process
import androidx.room.Room
import androidx.room.withTransaction
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.FakeAppRowResolver
import org.arkikeskus.launcher.data.HomeLayoutRepository
import org.arkikeskus.launcher.data.local.HomeItemEntity.Companion.DOCK
import org.arkikeskus.launcher.data.local.HomeItemEntity.Companion.HOME
import org.arkikeskus.launcher.data.profile.SwitchableProfile
import org.arkikeskus.launcher.model.AppItem
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Every home layout row belongs to a home profile, and the layout's code asks for rows without naming one:
 * it gets the rows of the profile that is on, or of the profile a caller named around its work. A row of
 * another profile is not there for it: not listed, not found by its id, not in the way of a cell.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileHomeItemDaoTest {
    private lateinit var db: LauncherDatabase
    private val active = SwitchableProfile()
    private lateinit var dao: HomeItemDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LauncherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.homeItemDao(active)
    }

    @After fun tearDown() = db.close()

    private fun row(pkg: String, cellX: Int = 0, cellY: Int = 0, container: Long = HOME) =
        HomeItemEntity(containerId = container, packageName = pkg, className = "Main", page = 0, cellX = cellX, cellY = cellY)

    private suspend fun everyRow() = dao.getAllOfEveryProfile()

    @Test fun aRowOfAnotherProfileIsNotSeen() = runTest {
        dao.insert(row("com.mail"))
        active.state.value = 2
        dao.insert(row("com.work", cellX = 1))

        assertThat(dao.getAll().map { it.packageName }).containsExactly("com.work")
        assertThat(dao.getContainer(HOME).map { it.packageName }).containsExactly("com.work")
        assertThat(dao.getAt(HOME, 0, 0, 0)).isNull()
        assertThat(dao.count(HOME, "com.mail", "Main", 0)).isEqualTo(0)
        assertThat(dao.childCount(HOME)).isEqualTo(1)
        active.state.value = 1
        assertThat(dao.getAll().map { it.packageName }).containsExactly("com.mail")
    }

    @Test fun anInsertIsStampedWithTheActiveProfile() = runTest {
        active.state.value = 3
        // Whatever the row says: the profile that is on decides.
        val id = dao.insert(row("com.mail").copy(homeProfileId = 1))

        assertThat(everyRow().single().homeProfileId).isEqualTo(3L)
        assertThat(dao.getById(id)!!.homeProfileId).isEqualTo(3L)
    }

    @Test fun theSameCellIsFreeInAnotherProfile() = runTest {
        dao.insert(row("com.mail"))
        active.state.value = 2

        dao.insert(row("com.work"))

        assertThat(everyRow().map { it.packageName to it.homeProfileId })
            .containsExactly("com.mail" to 1L, "com.work" to 2L)
    }

    @Test fun aRowIdOfAnotherProfileCannotBeTouched() = runTest {
        val mail = dao.insert(row("com.mail"))
        active.state.value = 2

        assertThat(dao.getById(mail)).isNull()
        dao.deleteById(mail)
        dao.moveById(mail, DOCK, 0, 3, 0)
        dao.renameFolder(mail, "Stolen")
        dao.updateSpans(mail, 2, 2)
        dao.updateWidgetId(mail, 7)
        dao.updateClassName(mail, "Other")
        dao.deleteByKey(HOME, "com.mail", "Main", 0)
        dao.deleteByContainers(listOf(HOME))
        dao.deleteOnPage(HOME, 0)
        dao.offsetPages(HOME, 0, 1)
        dao.clear()

        assertThat(everyRow()).containsExactly(row("com.mail").copy(id = mail, homeProfileId = 1))
    }

    @Test fun aNamedProfileWinsOverTheActiveOne() = runTest {
        inHomeProfile(2) { dao.insert(row("com.work")) }

        assertThat(dao.getAll()).isEmpty()
        assertThat(inHomeProfile(2) { dao.getAll() }.map { it.packageName }).containsExactly("com.work")
        assertThat(inHomeProfile(2) { dao.resolvedProfile() }).isEqualTo(2L)
        assertThat(dao.resolvedProfile()).isEqualTo(1L)
    }

    @Test fun theNamedProfileSurvivesARoomTransaction() = runTest {
        inHomeProfile(2) {
            db.withTransaction {
                dao.insert(row("com.work"))
                assertThat(dao.getAll()).hasSize(1)
            }
        }

        assertThat(everyRow().single().homeProfileId).isEqualTo(2L)
    }

    @Test fun anOperationKeepsItsProfileWhenTheActiveOneChangesInTheMiddle() = runTest {
        val repo = HomeLayoutRepository(db, dao, FakeAppRowResolver())
        dao.insert(row("com.first"))
        // The profile changes right after the operation has asked which one it works in.
        active.afterRead = { active.afterRead = null; active.state.value = 2 }

        val added = repo.addToHome(AppItem("com.mail", "Main", Process.myUserHandle(), 0L, "Mail"), columns = 4, rows = 6)

        assertThat(added).isTrue()
        assertThat(active.state.value).isEqualTo(2L)
        // Counted, placed and stored in profile 1, beside the row that was there: none of it in profile 2.
        assertThat(everyRow().map { Triple(it.packageName, it.homeProfileId, it.cellX) })
            .containsExactly(Triple("com.first", 1L, 0), Triple("com.mail", 1L, 1))
    }

    @Test fun replaceLayoutReplacesTheResolvedProfileOnly() = runTest {
        dao.insert(row("com.mail"))
        inHomeProfile(2) { dao.insert(row("com.work")) }

        dao.replaceLayout(listOf(row("com.new", cellX = 2), row("com.other", cellX = 3)))

        assertThat(everyRow().map { it.packageName to it.homeProfileId })
            .containsExactly("com.new" to 1L, "com.other" to 1L, "com.work" to 2L)
    }

    @Test fun theEveryProfileQueriesSeeEveryProfile() = runTest {
        dao.insert(row("com.clock").copy(appWidgetId = 11, widgetProvider = "com.clock/.W", spanX = 2))
        dao.insert(row("com.chat", cellX = 2).copy(shortcutId = "new"))
        inHomeProfile(2) {
            dao.insert(row("com.cal").copy(appWidgetId = 12, widgetProvider = "com.cal/.W", spanX = 2))
            dao.insert(row("com.chat", cellX = 2).copy(shortcutId = "group"))
        }

        assertThat(dao.boundWidgetIds()).containsExactly(11)
        assertThat(dao.boundWidgetIdsOfEveryProfile()).containsExactly(11, 12)
        assertThat(dao.shortcutIdsOfEveryProfile("com.chat", 0)).containsExactly("new", "group")
        assertThat(dao.getAllOfEveryProfile()).hasSize(4)
        assertThat(dao.profileIds()).containsExactly(1L, 2L)

        dao.deleteProfile(2)

        assertThat(dao.profileIds()).containsExactly(1L)
        assertThat(dao.getAll()).hasSize(2)
    }

    @Test fun observeAllFollowsTheActiveProfile() = runTest(UnconfinedTestDispatcher()) {
        dao.insert(row("com.mail"))
        inHomeProfile(2) { dao.insert(row("com.work")) }
        val seen = mutableListOf<List<String>>()
        val job = backgroundScope.launch { dao.observeAll().collect { rows -> seen += rows.map { it.packageName } } }

        assertThat(dao.observeAll().first().map { it.packageName }).containsExactly("com.mail")
        active.state.value = 2
        assertThat(dao.observeAll().first().map { it.packageName }).containsExactly("com.work")
        job.cancel()
    }

    @Test fun anEmptyLayoutStillNamesItsProfile() = runTest {
        dao.insert(row("com.mail"))

        assertThat(dao.observeAllWithProfile().first().first).isEqualTo(1L)
        active.state.value = 2
        val (profile, rows) = dao.observeAllWithProfile().first()
        assertThat(profile).isEqualTo(2L)
        assertThat(rows).isEmpty()
    }

    @Test fun withoutANamedOrSwitchedProfileEverythingIsTheFirstProfile() = runTest {
        // What every older test and a launcher with one profile get.
        val plain = db.homeItemDao()

        plain.insert(row("com.mail"))

        assertThat(everyRow().single().homeProfileId).isEqualTo(1L)
        assertThat(plain.getAll()).hasSize(1)
    }
}
