package org.arkikeskus.launcher.feature.settings

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The PayPal button opens the PayPal app directly when it is installed: the plain link only offers a
 * choice between the app and the browser where Android has not verified the app for paypal.me (seen on
 * a Pixel 8a). Without the app the plain link still opens the page in the browser.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class SupportLinkTest {
    /** Records what is started; a package-targeted start fails while that app is not [installed]. */
    private class Starts(base: Context, val installed: Boolean) : ContextWrapper(base) {
        val started = mutableListOf<Intent>()

        override fun startActivity(intent: Intent) {
            if (intent.`package` != null && !installed) throw ActivityNotFoundException()
            started += intent
        }
    }

    private val base get() = RuntimeEnvironment.getApplication()

    @Test fun withThePayPalAppInstalledTheButtonGoesStraightToIt() {
        val context = Starts(base, installed = true)

        openSupportLink(context, PAYPAL_URL)

        val intent = context.started.single()
        assertThat(intent.`package`).isEqualTo("com.paypal.android.p2pmobile")
        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.dataString).isEqualTo(PAYPAL_URL)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
    }

    @Test fun withoutTheAppThePlainLinkOpensInstead() {
        val context = Starts(base, installed = false)

        openSupportLink(context, PAYPAL_URL)

        val intent = context.started.single()
        assertThat(intent.`package`).isNull()
        assertThat(intent.dataString).isEqualTo(PAYPAL_URL)
    }

    @Test fun theOtherPagesAreAlwaysPlainLinks() {
        val context = Starts(base, installed = true)

        openSupportLink(context, SPONSOR_URL)
        openSupportLink(context, COFFEE_URL)

        assertThat(context.started.map { it.`package` }).containsExactly(null, null)
        assertThat(context.started.map { it.dataString }).containsExactly(SPONSOR_URL, COFFEE_URL).inOrder()
    }
}
