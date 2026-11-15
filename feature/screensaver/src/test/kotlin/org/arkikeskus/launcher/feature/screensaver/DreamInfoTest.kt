package org.arkikeskus.launcher.feature.screensaver

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DreamInfoTest {
    @Test
    fun `the settings activity is named by its class alone, so the gear of each build opens its own Settings`() {
        val parser = RuntimeEnvironment.getApplication().resources.getXml(R.xml.dream_info)
        var settings: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "dream") {
                settings = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "settingsActivity")
            }
        }

        // A name with a package and a slash would point the debug build's gear (another package) nowhere:
        // Android puts the screensaver's own package in front of a bare class name.
        assertThat(settings).isEqualTo("org.arkikeskus.launcher.ScreensaverSettingsAlias")
    }
}
