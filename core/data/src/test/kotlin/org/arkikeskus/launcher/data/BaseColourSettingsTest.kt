package org.arkikeskus.launcher.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Test

/**
 * The base colour the whole launcher is coloured from: orange until the user chooses, one of eleven
 * known values, part of the backup. The colour the Chameleon last took from the wallpaper is this
 * phone's own and stays out of it.
 */
class BaseColourSettingsTest {
    private fun newRepository() = SettingsRepository(InMemoryDataStore())

    @Test fun `the base colour is orange until chosen and an unknown value reads as orange`() = runTest {
        val repo = newRepository()
        assertThat(repo.settings.first().baseColour).isEqualTo(LauncherSettings.BASE_COLOUR_ORANGE)
        repo.setBaseColour(LauncherSettings.BASE_COLOUR_CHAMELEON)
        assertThat(repo.settings.first().baseColour).isEqualTo(LauncherSettings.BASE_COLOUR_CHAMELEON)
        repo.setBaseColour("ultraviolet")
        assertThat(repo.settings.first().baseColour).isEqualTo(LauncherSettings.BASE_COLOUR_ORANGE)
    }

    @Test fun `there are eleven base colours and orange is the first and the default`() {
        assertThat(LauncherSettings.BASE_COLOURS).containsExactly(
            "orange", "blue", "green", "violet", "rose", "teal", "yellow", "red", "black", "classic", "chameleon",
        ).inOrder()
        assertThat(LauncherSettings.BASE_COLOUR_DEFAULT).isEqualTo("orange")
        assertThat(LauncherSettings().baseColour).isEqualTo("orange")
    }

    /** Issue #28: the Classic choice is stored and read back as any other. */
    @Test fun `classic is a base colour that is stored and read back`() = runTest {
        val repo = newRepository()
        repo.setBaseColour(LauncherSettings.BASE_COLOUR_CLASSIC)
        assertThat(repo.settings.first().baseColour).isEqualTo("classic")
        assertThat(repo.exportRaw()).containsEntry("base_colour", "classic")
    }

    @Test fun `the base colour travels in the backup`() = runTest {
        val repo = newRepository()
        repo.setBaseColour(LauncherSettings.BASE_COLOUR_TEAL)
        val exported = repo.exportRaw()
        assertThat(exported).containsEntry("base_colour", "teal")
        val restored = newRepository().also { it.importRaw(exported) }
        assertThat(restored.settings.first().baseColour).isEqualTo("teal")
    }

    @Test fun `a backup from before the base colour restores as orange`() = runTest {
        val phone = newRepository()
        phone.setBaseColour(LauncherSettings.BASE_COLOUR_TEAL)
        phone.importRaw(mapOf("font_family" to LauncherSettings.FONT_SERIF))
        assertThat(phone.settings.first().baseColour).isEqualTo("orange")
    }

    @Test fun `a backup with a base colour of the wrong type or an unknown one reads as orange`() = runTest {
        val wrongType = newRepository().also { it.importRaw(mapOf("base_colour" to 7)) }
        assertThat(wrongType.settings.first().baseColour).isEqualTo("orange")
        val unknown = newRepository().also { it.importRaw(mapOf("base_colour" to "infrared")) }
        assertThat(unknown.settings.first().baseColour).isEqualTo("orange")
    }

    @Test fun `the chameleon's last colour is kept and cleared`() = runTest {
        val repo = newRepository()
        assertThat(repo.settings.first().chameleonLastArgb).isNull()
        repo.setChameleonLastArgb(0xFF1F8EF1.toInt())
        assertThat(repo.settings.first().chameleonLastArgb).isEqualTo(0xFF1F8EF1.toInt())
        repo.setChameleonLastArgb(null)
        assertThat(repo.settings.first().chameleonLastArgb).isNull()
    }

    @Test fun `the chameleon's last colour stays on this phone`() = runTest {
        val repo = newRepository()
        repo.setChameleonLastArgb(0xFF1F8EF1.toInt())
        assertThat(repo.exportRaw()).doesNotContainKey("chameleon_last_argb")

        // A file cannot bring one in, as a number of either width.
        val other = newRepository().also { it.importRaw(mapOf("chameleon_last_argb" to 0xFF00FF00L)) }
        assertThat(other.settings.first().chameleonLastArgb).isNull()

        // And restoring a backup does not wipe this phone's own.
        repo.importRaw(mapOf("base_colour" to "chameleon", "chameleon_last_argb" to 0xFF00FF00L))
        assertThat(repo.settings.first().chameleonLastArgb).isEqualTo(0xFF1F8EF1.toInt())
        assertThat(repo.settings.first().baseColour).isEqualTo("chameleon")
    }
}
