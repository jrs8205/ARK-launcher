package org.arkikeskus.launcher

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Every surface that tells its icons which icon pack to use tells them the icon shape too (issue #26). The
 * two travel together: a surface that provided only the pack would draw its icons in the phone's shape
 * beside a home screen of hexagons. Read from the sources, so a surface added later cannot leave it out.
 */
class IconShapeProvidedTest {
    private val mainSources = listOf("../app/src/main", "../core", "../feature")

    private fun kotlinMainFiles(): List<File> = mainSources.flatMap { dir ->
        val root = File(dir)
        assertWithMessage("$root is missing: the test runs from the app module's directory").that(root.isDirectory).isTrue()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.invariantSeparatorsPath.let { "/src/main/" in it && "/build/" !in it } }
            .toList()
    }

    @Test
    fun `a surface that provides the icon pack provides the icon shape`() {
        val providers = kotlinMainFiles().filter { "LocalIconPack provides" in it.readText() }

        // The shell's floating drag icon, the home screen, the app drawer and Settings.
        assertThat(providers.map { it.name }).containsExactly(
            "LauncherShell.kt", "HomeScreen.kt", "AppDrawerScreen.kt", "SettingsScreen.kt",
        )
        for (file in providers) {
            assertWithMessage("${file.name} provides the icon pack without the icon shape")
                .that(file.readText()).contains("LocalIconShape provides")
        }
    }
}
