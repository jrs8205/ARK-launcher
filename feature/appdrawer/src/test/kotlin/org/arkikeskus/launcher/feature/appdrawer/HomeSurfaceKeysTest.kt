package org.arkikeskus.launcher.feature.appdrawer

import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.junit.Test

/**
 * Which apps "are on the home screen" for the drawer's hide-home-apps setting: the apps on the pages,
 * inside home folders, and in the dock AS SHOWN — a dock that is off, or a dock row past the dock's
 * columns, is not on screen, so those apps stay in the drawer.
 */
class HomeSurfaceKeysTest {
    private fun app(id: Long, container: Long, pkg: String, slot: Int = 0) =
        HomeItemEntity(id = id, containerId = container, packageName = pkg, className = "$pkg.Main", page = 0, cellX = slot, cellY = 0)

    private fun folder(id: Long, container: Long, slot: Int = 0) =
        HomeItemEntity(id = id, containerId = container, folderName = "F", page = 0, cellX = slot, cellY = 0)

    private val installed = setOf("a/a.Main/0", "b/b.Main/0", "c/c.Main/0", "d/d.Main/0", "e/e.Main/0", "f/f.Main/0", "g/g.Main/0")

    @Test fun pagesAndHomeFoldersCount() {
        val items = listOf(
            app(1, HomeItemEntity.HOME, "a"),
            folder(2, HomeItemEntity.HOME),
            app(3, container = 2, pkg = "b"),
        )
        assertThat(homeSurfaceKeys(items, installed, dockEnabled = true, dockColumns = 5)).containsExactly("a/a.Main/0", "b/b.Main/0")
    }

    @Test fun theDockCountsOnlyAsShown() {
        val items = listOf(
            app(1, HomeItemEntity.DOCK, "a", slot = 0),
            folder(2, HomeItemEntity.DOCK, slot = 1),
            app(3, container = 2, pkg = "b"),
            app(4, HomeItemEntity.DOCK, "zz.gone", slot = 2), // uninstalled: skipped by the dock, takes no slot
            app(5, HomeItemEntity.DOCK, "c", slot = 3),
            app(6, HomeItemEntity.DOCK, "d", slot = 4), // past three columns: not shown
        )
        assertThat(homeSurfaceKeys(items, installed, dockEnabled = true, dockColumns = 3))
            .containsExactly("a/a.Main/0", "b/b.Main/0", "c/c.Main/0")
        assertThat(homeSurfaceKeys(items, installed, dockEnabled = false, dockColumns = 3)).isEmpty()
    }

    @Test fun shortcutsWidgetsAndBuiltinsAreNotApps() {
        val items = listOf(
            HomeItemEntity(id = 1, containerId = HomeItemEntity.HOME, packageName = "a", className = "a.Main", shortcutId = "s", page = 0, cellX = 0, cellY = 0),
            HomeItemEntity(id = 2, containerId = HomeItemEntity.HOME, appWidgetId = 7, widgetProvider = "w/W", page = 0, cellX = 1, cellY = 0, spanX = 2, spanY = 1),
            HomeItemEntity(id = 3, containerId = HomeItemEntity.HOME, builtinType = "clock", page = 0, cellX = 0, cellY = 1),
        )
        assertThat(homeSurfaceKeys(items, installed, dockEnabled = true, dockColumns = 5)).isEmpty()
    }
}
