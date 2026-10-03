package org.arkikeskus.launcher.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** App keys ("package/class/userSerial") kept in the settings, and following renamed activities. */
class SettingsAppKeysTest {

    @Test
    fun `renamedAppKeys follows a single new launcher activity only`() {
        val resolver = FakeAppRowResolver().apply {
            targets["a"] = PackageTargets.Launchable(listOf("a.New"))
            targets["b"] = PackageTargets.Launchable(listOf("b.One", "b.Two"))
            targets["c"] = PackageTargets.Launchable(emptyList())
            targets["ok"] = PackageTargets.Launchable(listOf("ok.Main"))
        }
        val renames = renamedAppKeys(
            listOf("a/a.Old/0", "a/a.Old/10", "b/b.Old/0", "c/c.Old/0", "ok/ok.Main/0", "unknown/u.X/0", "garbage"),
            resolver,
        )
        assertThat(renames).containsExactly("a/a.Old/0", "a/a.New/0", "a/a.Old/10", "a/a.New/10")
    }

    @Test
    fun `renameAppKeys rewrites every setting that refers to an app`() = runTest {
        val repo = SettingsRepository(InMemoryDataStore())
        listOf("x/x.M/0", "a/a.Old/0", "a/a.New/0").forEach { repo.addToDock(it) }
        repo.setAppHidden("a/a.Old/0", true)
        repo.setCustomLabel("a/a.Old/0", "Mail")
        repo.setCustomLabel("x/x.M/0", "X")
        val folder = repo.createDrawerFolder("Tools")
        repo.addAppsToDrawerFolder(folder, listOf("a/a.Old/0", "x/x.M/0"))
        repo.setLeftSwipeAppKey("a/a.Old/0")
        assertThat(repo.referencedAppKeys()).containsExactly("x/x.M/0", "a/a.Old/0", "a/a.New/0")

        repo.renameAppKeys(mapOf("a/a.Old/0" to "a/a.New/0"))

        // The dock already held the new key: the renamed entry collapses into it, nothing duplicates.
        assertThat(repo.dockFavorites.first()).containsExactly("x/x.M/0", "a/a.New/0").inOrder()
        assertThat(repo.hiddenApps.first()).containsExactly("a/a.New/0")
        assertThat(repo.customLabels.first()).containsExactly("a/a.New/0", "Mail", "x/x.M/0", "X")
        assertThat(repo.drawerFolders.first().single().appKeys).containsExactly("a/a.New/0", "x/x.M/0").inOrder()
        assertThat(repo.settings.first().leftSwipeAppKey).isEqualTo("a/a.New/0")
    }

    @Test
    fun `rewriteAppKeys drops entries the transform rejects and keeps folders and labels intact`() {
        val drop: (String) -> String? = { if (it.startsWith("gone/")) null else it }
        assertThat(SettingsRepository.rewriteAppKeys("dock_favorites", "gone/G/0\nk/K/0", drop)).isEqualTo("k/K/0")
        assertThat(SettingsRepository.rewriteAppKeys("custom_labels", "gone/G/0\tOld\nk/K/0\tLa\tbel", drop))
            .isEqualTo("k/K/0\tLa\tbel")
        assertThat(SettingsRepository.rewriteAppKeys("drawer_folders", "1\tEmpty\n2\tF\tgone/G/0\tk/K/0", drop))
            .isEqualTo("1\tEmpty\n2\tF\tk/K/0")
        assertThat(SettingsRepository.rewriteAppKeys("left_swipe_app_key", "gone/G/0", drop)).isEmpty()
        assertThat(SettingsRepository.rewriteAppKeys("icon_pack_package", "gone/G/0", drop)).isEqualTo("gone/G/0")
    }
}
