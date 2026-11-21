package org.arkikeskus.launcher.feature.settings

import android.app.Application
import android.content.res.Configuration
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.arkikeskus.launcher.data.profile.HomeProfileRepository.Deleted
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
}
