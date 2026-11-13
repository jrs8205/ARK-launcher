package org.arkikeskus.launcher

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Settings, the app drawer and the shared components are coloured from the palette, so a literal
 * black or white there is either a colour with a meaning of its own or a mistake: what sits on a
 * fill must take the fill's content colour (a white thumb on a yellow switch is 1.4:1). The files
 * that may name black or white are listed with their reason; a new one has to be added here on
 * purpose.
 */
class NoFixedColourOnThemedSurfacesTest {
    private val themedSources = listOf(
        "../feature/settings/src/main/kotlin",
        "../feature/appdrawer/src/main/kotlin",
        "../core/ui/src/main/kotlin/org/arkikeskus/launcher/ui/expressive",
        "../core/ui/src/main/kotlin/org/arkikeskus/launcher/ui/component",
    )

    private val allowed = mapOf(
        // The dock preview is drawn on a stand-in wallpaper, like the dock itself on the real one.
        "DockSection.kt" to "a preview on a stand-in wallpaper",
        // Decides black or white text by contrast with the user's own drawer colour.
        "DrawerColors.kt" to "black or white chosen by contrast",
        // The badge is a fixed red with white on it everywhere.
        "NotificationBadge.kt" to "a colour with a meaning",
        // The check on a chosen colour swatch reads on the swatch, which is no themed surface.
        "SwatchMark.kt" to "black or white chosen by contrast",
    )

    private val fixed = Regex("""Color\.(White|Black)\b""")

    private fun kotlinFiles(): List<File> = themedSources.flatMap { dir ->
        val root = File(dir)
        assertWithMessage("$root is missing: the test runs from the app module's directory").that(root.isDirectory).isTrue()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun codeOf(line: String): String = line.substringBefore("//")

    @Test fun blackAndWhiteAreNamedOnlyWhereTheyHaveAReason() {
        val offenders = kotlinFiles().filter { it.name !in allowed }.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> fixed.containsMatchIn(codeOf(line)) }
                .map { (i, line) -> "${file.name}:${i + 1}: ${line.trim()}" }
        }
        assertWithMessage("a fixed black or white on a themed surface; use the palette (onFill on a fill)")
            .that(offenders).isEmpty()
    }

    @Test fun theAllowListNamesOnlyFilesThatStillNeedIt() {
        val files = kotlinFiles()
        for (name in allowed.keys) {
            val file = files.singleOrNull { it.name == name }
            assertWithMessage("$name is on the allow list but is not among the themed sources").that(file).isNotNull()
            assertWithMessage("$name no longer names black or white: take it off the allow list")
                .that(file!!.readLines().any { fixed.containsMatchIn(codeOf(it)) }).isTrue()
        }
    }
}
