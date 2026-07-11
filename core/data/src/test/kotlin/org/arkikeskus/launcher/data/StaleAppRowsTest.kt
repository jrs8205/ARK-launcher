package org.arkikeskus.launcher.data

import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.junit.Test

class StaleAppRowsTest {

    @Test
    fun `selects only uninstalled app, shortcut and folder-child rows`() {
        val rows = listOf(
            HomeItemEntity(id = 1, packageName = "com.gone", className = "A", page = 0, cellX = 0, cellY = 0), // app → stale
            HomeItemEntity(id = 2, packageName = "com.here", className = "B", page = 0, cellX = 1, cellY = 0), // installed → kept
            HomeItemEntity(id = 3, packageName = "com.gone", shortcutId = "s1", page = 0, cellX = 2, cellY = 0), // shortcut → stale
            HomeItemEntity(id = 4, folderName = "Tools", page = 0, cellX = 3, cellY = 0), // folder row → kept
            HomeItemEntity(id = 5, containerId = 4, packageName = "com.gone", className = "A", page = 0, cellX = 0, cellY = 0), // child → stale
            HomeItemEntity(id = 6, widgetProvider = "com.gone/P", appWidgetId = 7, page = 1, cellX = 0, cellY = 0), // widget → kept
            HomeItemEntity(id = 7, builtinType = "notifications", page = 1, cellX = 0, cellY = 1), // builtin → kept
        )
        val stale = HomeLayoutRepository.staleAppRowIds(rows) { pkg, _ -> pkg != "com.gone" }
        assertThat(stale).containsExactly(1L, 3L, 5L)
    }

    @Test
    fun `profile serial participates in the verdict`() {
        val rows = listOf(
            HomeItemEntity(id = 1, packageName = "com.a", className = "A", userSerial = 0, page = 0, cellX = 0, cellY = 0),
            HomeItemEntity(id = 2, packageName = "com.a", className = "A", userSerial = 10, page = 0, cellX = 1, cellY = 0),
        )
        val stale = HomeLayoutRepository.staleAppRowIds(rows) { _, serial -> serial != 10L }
        assertThat(stale).containsExactly(2L)
    }

    @Test
    fun `blank package rows are never stale`() {
        val rows = listOf(
            HomeItemEntity(id = 1, packageName = "", className = "", page = 0, cellX = 0, cellY = 0),
        )
        assertThat(HomeLayoutRepository.staleAppRowIds(rows) { _, _ -> false }).isEmpty()
    }
}
