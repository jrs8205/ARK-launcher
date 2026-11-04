package org.arkikeskus.launcher.feature.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * The font the user chose (Settings > Look) lives in the theme's typography, i.e. in LocalTextStyle; a Text given a
 * bare TextStyle(...) of its own replaces that style wholesale and falls back to the platform font. Every own text of
 * the home screen -- the clock, the status bar, the built-in widgets -- must build its style with themedTextStyle(...)
 * (core/ui), which merges it on top of the theme's. This reads the module's sources so no new bare style slips in.
 */
class OwnTextsFollowTheThemeFontTest {
    @Test fun noHomeTextIsGivenABareStyleOfItsOwn() {
        val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        assertThat(sources).isNotEmpty()
        val bare = Regex("""style\s*=\s*(TextStyle\(|[A-Z]\w*Style\b)""")
        val offenders = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                val offends = bare.containsMatchIn(line) && "themedTextStyle(" !in line && "LocalTextStyle" !in line
                if (offends) "${file.name}:${i + 1}: ${line.trim()}" else null
            }
        }
        assertThat(offenders).isEmpty()
    }
}
