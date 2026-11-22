package org.arkikeskus.launcher.ui

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Review round 17. The notice of a home profile that could not be opened waits until the home screen is what
 * the user sees, and by then the user may have switched back to that profile in Settings (its store read
 * again). The text said "Another profile is shown instead", which was then false of the home screen it was
 * shown on. It says what HAPPENED, which is true whichever profile is on when it is read: that a profile
 * could not be opened and the launcher switched to another one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class HomeNoticeTextTest {
    private val res = RuntimeEnvironment.getApplication().resources

    @Test fun theNoticeOfAProfileThatWasLeftSaysWhatHappenedAndNothingOfWhatIsShownNow() {
        val text = res.getString(HomeNotice.ProfileNotRead.message)

        assertThat(text).contains("could not be opened")
        assertThat(text).contains("switched to another")
        assertThat(text).doesNotContain("is shown")
    }
}
