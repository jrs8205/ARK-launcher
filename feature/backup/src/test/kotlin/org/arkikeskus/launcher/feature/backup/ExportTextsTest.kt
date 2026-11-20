package org.arkikeskus.launcher.feature.backup

import android.content.res.Configuration
import android.content.res.Resources
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.arkikeskus.launcher.model.HomeProfile
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * A backup file holds ONE home profile, the one that is on. With several profiles the backup screen says which:
 * on the export button, in its result and beside the time of the last export, so nobody takes the file for a
 * backup of every profile. With one profile the texts are what they always were.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en")
class ExportTextsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val res: Resources = context.resources

    private fun resourcesIn(language: String): Resources {
        val config = Configuration(res.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        return context.createConfigurationContext(config).resources
    }

    private val oneProfile = profilesARestoreDeletes(listOf(HomeProfile(1, "")), activeId = 1)
    private val workIsOn = profilesARestoreDeletes(listOf(HomeProfile(1, ""), HomeProfile(2, "Work")), activeId = 2)
    private val unnamedIsOn = profilesARestoreDeletes(listOf(HomeProfile(1, ""), HomeProfile(2, "Work")), activeId = 1)

    @Test fun theProfilesCarryTheNameOfTheOneThatIsOn() {
        assertThat(workIsOn.activeName).isEqualTo("Work")
        assertThat(unnamedIsOn.activeName).isEmpty()
    }

    @Test fun withOneProfileTheTextsAreWhatTheyAlwaysWere() {
        assertThat(exportedProfileName(res, oneProfile)).isNull()
        assertThat(exportButtonText(res, oneProfile)).isEqualTo("Export to file")
        assertThat(lastBackupText(res, oneProfile, "1/2/26, 3:04 PM")).isEqualTo("Last: 1/2/26, 3:04 PM")
        assertThat(backupEventMessage(res, BackupEvent.Exported("b.json"))).isEqualTo("Backup saved")
    }

    /** Not read yet: the plain texts, never a name that is a guess. */
    @Test fun beforeTheProfilesAreReadNoProfileIsNamed() {
        assertThat(exportedProfileName(res, null)).isNull()
        assertThat(exportButtonText(res, null)).isEqualTo("Export to file")
        assertThat(lastBackupText(res, null, "now")).isEqualTo("Last: now")
    }

    @Test fun withSeveralProfilesTheButtonTheResultAndTheTimeNameTheOneThatIsExported() {
        assertThat(exportedProfileName(res, workIsOn)).isEqualTo("Work")
        assertThat(exportButtonText(res, workIsOn)).isEqualTo("Export profile “Work” to file")
        assertThat(lastBackupText(res, workIsOn, "1/2/26, 3:04 PM")).isEqualTo("Last backup of “Work”: 1/2/26, 3:04 PM")
        assertThat(backupEventMessage(res, BackupEvent.Exported("b.json", profile = "Work")))
            .isEqualTo("Backup of profile “Work” saved")
    }

    /** A profile the user never named goes under the name it is shown with everywhere, not an empty one. */
    @Test fun anUnnamedProfileIsNamedAsItIsShownEverywhere() {
        assertThat(exportedProfileName(res, unnamedIsOn)).isEqualTo("Home")
        assertThat(exportButtonText(res, unnamedIsOn)).isEqualTo("Export profile “Home” to file")
        assertThat(lastBackupText(res, unnamedIsOn, "now")).isEqualTo("Last backup of “Home”: now")
        assertThat(backupEventMessage(res, BackupEvent.Exported("b.json", profile = "")))
            .isEqualTo("Backup of profile “Home” saved")
        assertThat(exportButtonText(resourcesIn("fi"), unnamedIsOn)).isEqualTo("Vie profiili ”Koti” tiedostoon")
    }

    /** A translation that lost a placeholder would name no profile, or no time. */
    @Test fun everyLanguageNamesTheProfileAndTheTime() {
        for (language in listOf("de", "es", "fi", "fr", "pl", "pt", "ru", "sv", "tr")) {
            val translated = resourcesIn(language)
            assertWithMessage(language).that(exportButtonText(translated, workIsOn)).contains("Work")
            assertWithMessage(language).that(backupEventMessage(translated, BackupEvent.Exported("b.json", profile = "Work")))
                .contains("Work")
            val last = lastBackupText(translated, workIsOn, "3:04")
            assertWithMessage(language).that(last).contains("Work")
            assertWithMessage(language).that(last).contains("3:04")
            for (text in listOf(R.string.backup_export_profile, R.string.backup_exported_profile, R.string.backup_last_time_profile)) {
                assertWithMessage("$language is translated").that(translated.getString(text)).isNotEqualTo(res.getString(text))
            }
        }
    }
}
