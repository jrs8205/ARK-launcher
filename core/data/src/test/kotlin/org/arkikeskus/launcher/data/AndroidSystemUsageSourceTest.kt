package org.arkikeskus.launcher.data

import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Android's usage statistics refusing the question. */
@Implements(UsageStatsManager::class)
class RefusingUsageStats {
    @Implementation
    protected fun queryEvents(beginTime: Long, endTime: Long): UsageEvents = throw SecurityException("refused")
}

/** Android's usage statistics while the user is still locked after a restart: no answer at all. */
@Implements(UsageStatsManager::class)
class LockedUsageStats {
    @Implementation
    protected fun queryEvents(beginTime: Long, endTime: Long): UsageEvents? = null
}

/**
 * What Android answers of the screens that came to the front, as the launcher reads it. "Nothing came to the
 * front" is an answer; a refusal, or no answer, is not one, and must not look like it: the count would take
 * the span for read and never see what was opened in it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidSystemUsageSourceTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val source = AndroidSystemUsageSource(context)

    @Test fun theScreensThatCameToTheFrontAreReadOldestFirst() {
        val android = shadowOf(context.getSystemService(UsageStatsManager::class.java))
        android.addEvent("com.mail", 100L, UsageEvents.Event.ACTIVITY_RESUMED)
        android.addEvent("com.mail", 200L, UsageEvents.Event.ACTIVITY_PAUSED)
        android.addEvent("com.maps", 300L, UsageEvents.Event.ACTIVITY_RESUMED)

        val resumed = source.resumedBetween(0L, 1_000L)

        assertThat(resumed!!.map { it.packageName to it.timeMs }).containsExactly("com.mail" to 100L, "com.maps" to 300L).inOrder()
    }

    /** Things happened in the span, and no screen came to the front: that is an answer. */
    @Test fun eventsWithNoScreenComingToTheFrontAreAnAnswer() {
        val android = shadowOf(context.getSystemService(UsageStatsManager::class.java))
        android.addEvent("com.mail", 200L, UsageEvents.Event.ACTIVITY_PAUSED)

        val resumed = source.resumedBetween(0L, 1_000L)

        assertThat(resumed).isNotNull()
        assertThat(resumed).isEmpty()
    }

    /**
     * No event of any kind. Android's own query hands back this same empty answer when its service did not
     * answer or the call to it failed, so it is not one to go by: the span stays to be read again. (Nothing
     * came to the front in a span with no event at all, so nothing is lost by that.)
     */
    @Test fun anAnswerWithNoEventAtAllIsNotOneToGoBy() {
        assertThat(source.resumedBetween(0L, 1_000L)).isNull()
    }

    @Config(shadows = [RefusingUsageStats::class])
    @Test fun aRefusalIsNoAnswer() {
        assertThat(source.resumedBetween(0L, 1_000L)).isNull()
    }

    @Config(shadows = [LockedUsageStats::class])
    @Test fun noAnswerWhileTheUserIsLockedIsNoAnswer() {
        assertThat(source.resumedBetween(0L, 1_000L)).isNull()
    }
}
