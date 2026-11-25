package org.arkikeskus.launcher.feature.settings

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import android.app.Application
import android.content.res.Configuration
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.arkikeskus.launcher.data.profile.HomeProfileRepository.Deleted
import org.arkikeskus.launcher.data.profile.HomeProfileRepository.GridChange
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * What Settings says when a change to the profiles did not happen. A deletion can fail in two ways that leave
 * different things behind, and the user is told which: a profile that is whole, or one that is still listed
 * with nothing on it and is to be deleted again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class ProfileNoticesTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    @Test fun aDeletionSaysWhatItLeftAndNothingWhenItWentAsAsked() {
        assertThat(deletionNotice(Deleted.Done)).isNull()
        assertThat(deletionNotice(Deleted.Refused)).isNull()
        assertThat(deletionNotice(Deleted.Failed)).isEqualTo(SettingsNotice.PROFILE_NOT_DELETED)
        assertThat(deletionNotice(Deleted.LeftEmpty)).isEqualTo(SettingsNotice.PROFILE_LEFT_EMPTY)
    }

    @Test fun everyNoticeHasATextOfItsOwnInEveryLanguage() {
        val english = SettingsNotice.entries.associateWith { context.getString(settingsNoticeText(it)) }
        assertThat(english.values.toSet()).hasSize(SettingsNotice.entries.size)
        assertThat(english.getValue(SettingsNotice.PROFILE_LEFT_EMPTY)).contains("Delete it again")
        for (language in listOf("de", "es", "fi", "fr", "pl", "pt", "ru", "sv", "tr")) {
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
            val res = context.createConfigurationContext(config).resources
            for ((notice, text) in english) {
                assertWithMessage("$notice in $language").that(res.getString(settingsNoticeText(notice))).isNotEqualTo(text)
            }
        }
    }

    @Test fun aRefusedSettingsWriteReportsFailureAndCanBeRetried() = kotlinx.coroutines.runBlocking {
        val file = java.io.File.createTempFile("settings", ".preferences_pb", context.cacheDir).also { it.delete() }
        val owner = kotlinx.coroutines.SupervisorJob()
        val store = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(owner + kotlinx.coroutines.Dispatchers.IO),
        ) { file }
        val settings = org.arkikeskus.launcher.data.SettingsRepository(store)
        try {
            settings.setShowFrequentApps(false)
            val scratch = java.io.File(file.path + ".tmp")
            check(scratch.mkdir())
            val notice = settingsChange(1) { settings.setShowFrequentApps(true) }
            assertThat(notice).isEqualTo(SettingsNotice.PROFILE_NOT_CHANGED)
            assertThat(settings.settings.first().showFrequentApps).isFalse()
            if (scratch.exists()) check(scratch.delete())
            assertThat(settingsChange(1) { settings.setShowFrequentApps(true) }).isNull()
            assertThat(settings.settings.first().showFrequentApps).isTrue()
        } finally {
            owner.cancelAndJoin()
        }
    }

    @Test fun cancellingASettingsWritePropagates() = kotlinx.coroutines.runBlocking {
        val cancelled = kotlinx.coroutines.CancellationException("leaving settings")
        val error = runCatching { settingsChange(1) { throw cancelled } }.exceptionOrNull()
        assertThat(error).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
        assertThat(error).hasMessageThat().isEqualTo(cancelled.message)
    }

    @Test fun aFailedGridWriteAfterReflowHasItsOwnNotice() {
        assertThat(gridNotice(GridChange.Refused)).isNull()
        assertThat(gridNotice(GridChange.Saved)).isNull()
        assertThat(gridNotice(GridChange.Failed)).isEqualTo(SettingsNotice.PROFILE_NOT_CHANGED)
        assertThat(gridNotice(GridChange.ReflowedButNotSaved)).isEqualTo(SettingsNotice.GRID_REFLOWED)
    }

    @Test fun finnishGridReflowNoticeUsesTheApprovedWording() {
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag("fi")) }
        val text = context.createConfigurationContext(config).getString(settingsNoticeText(SettingsNotice.GRID_REFLOWED))
        assertThat(text).isEqualTo("Ruudukon kokoa ei voitu tallentaa. Kotinäytön kohteet ovat saattaneet siirtyä.")
    }
}
