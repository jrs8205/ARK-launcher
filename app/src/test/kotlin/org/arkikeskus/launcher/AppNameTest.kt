package org.arkikeskus.launcher

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.feature.screensaver.ScreensaverService
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/** The app's name as it is written: ARK-Launcher, the same in every language. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppNameTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun nameIn(language: String): String {
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        return context.createConfigurationContext(config).getString(R.string.app_name)
    }

    @Test
    fun `the name is ARK-Launcher in every language`() {
        for (language in listOf("en", "fi", "de", "es", "fr", "pl", "pt", "ru", "sv", "tr")) {
            assertThat(nameIn(language)).isEqualTo("ARK-Launcher")
        }
    }

    @Test
    fun `the screensaver is listed under the app's name`() {
        val service = context.packageManager.getServiceInfo(ComponentName(context, ScreensaverService::class.java), 0)

        assertThat(service.loadLabel(context.packageManager).toString()).isEqualTo("ARK-Launcher")
    }
}
