package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Process
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.AppItem
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The first-run dock seeds the phone's default dialer. On phones whose dialer package also holds
 * Contacts as a second launcher entry, the slot took the package's alphabetically first entry, so
 * an English phone started with Contacts where Phone belongs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DefaultDockAppsTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    private fun app(cls: String, label: String, serial: Long = 0L) = AppItem("com.dialer", cls, Process.myUserHandle(), serial, label)

    @Test fun thePhoneSlotTakesTheActivityTheDialIntentResolvesTo() {
        val phone = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = "com.dialer"; name = "com.dialer.Phone" }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_DIAL), phone)
        val apps = listOf(app("com.dialer.Contacts", "Contacts"), app("com.dialer.Phone", "Phone"))

        val keys = resolveDefaultDockKeys(context, apps)

        assertThat(keys.first()).isEqualTo("com.dialer/com.dialer.Phone/0")
    }

    /** A work-profile copy of the same activity, listed first (the list is sorted by label, and a
     *  restored custom label can put it there), must not win over the own profile's entry. */
    @Test fun theOwnProfilesEntryWinsOverAWorkCopyListedFirst() {
        val phone = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = "com.dialer"; name = "com.dialer.Phone" }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_DIAL), phone)
        val apps = listOf(
            app("com.dialer.Phone", "Dialer (work)", serial = 10L),
            app("com.dialer.Contacts", "Contacts"),
            app("com.dialer.Phone", "Phone"),
        )

        val keys = resolveDefaultDockKeys(context, apps)

        assertThat(keys.first()).isEqualTo("com.dialer/com.dialer.Phone/0")
    }
}
