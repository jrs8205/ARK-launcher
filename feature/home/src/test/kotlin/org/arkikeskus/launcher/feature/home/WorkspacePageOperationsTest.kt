package org.arkikeskus.launcher.feature.home

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Test

class WorkspacePageOperationsTest {
    private class Store(home: Int = 0, count: Int = 0, pages: Set<Int> = emptySet()) {
        var settings = LauncherSettings(homePage = home, homePageCount = count)
        var occupied = pages
        var beforeInsert: suspend () -> Unit = {}
        var beforeSave: suspend () -> Unit = {}
        val operations = WorkspacePageOperations(
            settings = { settings }, occupiedPages = { occupied },
            insertRows = { at -> beforeInsert(); occupied = occupied.map { if (it >= at) it + 1 else it }.toSet() },
            removeRows = { at ->
                if (at in occupied) false else {
                    occupied = occupied.map { if (it > at) it - 1 else it }.toSet()
                    true
                }
            },
            save = { home, count ->
                beforeSave()
                settings = settings.copy(homePage = home, homePageCount = count)
            },
        )
    }

    @Test fun `left insertion preserves the original empty implicit page`() = runTest {
        val store = Store()
        assertThat(store.operations.insert(0)).isEqualTo(0)
        assertThat(store.settings.homePageCount).isEqualTo(2)
        assertThat(store.settings.homePage).isEqualTo(1)
    }

    @Test fun `two insertions queued during Room write keep content and counters aligned`() = runTest {
        val store = Store(pages = setOf(0))
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        store.beforeInsert = { started.complete(Unit); resume.await() }
        val first = async { store.operations.insert(0) }
        started.await()
        val second = async { store.operations.insert(0) }
        resume.complete(Unit)
        first.await(); second.await()
        assertThat(store.occupied).containsExactly(2)
        assertThat(store.settings.homePage).isEqualTo(2)
        assertThat(store.settings.homePageCount).isEqualTo(3)
    }

    @Test fun `insertion queued during settings write reads the committed counters`() = runTest {
        val store = Store()
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        store.beforeSave = { started.complete(Unit); resume.await() }
        val first = async { store.operations.insert(0) }
        started.await()
        val second = async { store.operations.insert(0) }
        resume.complete(Unit)
        first.await(); second.await()
        assertThat(store.settings.homePage).isEqualTo(2)
        assertThat(store.settings.homePageCount).isEqualTo(3)
    }

    @Test fun `removing last home page returns destination even when settings already updated`() = runTest {
        val store = Store(home = 2, count = 3)
        val target = store.operations.remove(2)
        assertThat(store.settings.homePageCount).isEqualTo(2)
        assertThat(target).isEqualTo(1)
        assertThat(store.settings.homePage).isEqualTo(1)
    }

    @Test fun `nonempty and only page removals leave state unchanged`() = runTest {
        val single = Store()
        assertThat(single.operations.remove(0)).isNull()
        val occupied = Store(count = 2, pages = setOf(1))
        assertThat(occupied.operations.remove(1)).isNull()
        assertThat(occupied.settings.homePageCount).isEqualTo(2)
    }
}
