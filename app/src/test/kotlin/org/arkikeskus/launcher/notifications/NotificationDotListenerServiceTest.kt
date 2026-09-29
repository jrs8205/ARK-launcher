package org.arkikeskus.launcher.notifications

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Process
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.arkikeskus.launcher.data.NotificationBadgeRepository
import org.arkikeskus.launcher.data.PersonEntry
import org.arkikeskus.launcher.data.SettingsRepository
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNotificationListenerService
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationDotListenerServiceTest {
    private lateinit var service: NotificationDotListenerService
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val me = Person.Builder().setName("Me").setKey("self").build()
    private val anna = Person.Builder().setName("Anna").setKey("anna").build()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // The parser and callback are exercised without binding a real notification listener.
        service = NotificationDotListenerService()
        ReflectionHelpers.callInstanceMethod<Unit>(service, "attachBaseContext", from(Context::class.java, context))
        service.badgeRepository = NotificationBadgeRepository()
        service.settingsRepository = SettingsRepository(object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        })
    }

    @After fun tearDown() {
        service.onDestroy()
        Dispatchers.resetMain()
    }

    private fun enableBatch() {
        ReflectionHelpers.setField(service, "connected", true)
        ReflectionHelpers.setField(service, "heldLoaded", true)
        ReflectionHelpers.setField(service, "settingsReady", true)
        ReflectionHelpers.setField(service, "batchEnabled", true)
        val time = java.time.LocalTime.now()
        ReflectionHelpers.setField(service, "batchTimes", listOf((time.hour * 60 + time.minute + 60) % 1440))
    }

    private fun post(notification: StatusBarNotification) {
        service.cancelNotification(notification.key)
        Shadow.extract<ShadowNotificationListenerService>(service).addActiveNotification(notification)
        service.onNotificationPosted(notification)
    }

    private fun sbn(notification: Notification) = StatusBarNotification(
        "chat.app", "chat.app", 1, null, 1000, 0, 0, notification, Process.myUserHandle(), System.currentTimeMillis(),
    )

    private fun person(notification: StatusBarNotification): PersonEntry? = ReflectionHelpers.callInstanceMethod(
        service, "personEntry", from(StatusBarNotification::class.java, notification),
        from(Long::class.javaPrimitiveType!!, 0L), from(RankingMap::class.java, null), from(Ranking::class.java, Ranking()),
    )

    private fun app(notification: StatusBarNotification): PersonEntry? = ReflectionHelpers.callInstanceMethod(
        service, "appEntry", from(StatusBarNotification::class.java, notification), from(Long::class.javaPrimitiveType!!, 0L),
    )

    private fun chat(vararg incoming: Boolean): StatusBarNotification {
        val style = NotificationCompat.MessagingStyle(me)
        incoming.forEachIndexed { i, other -> style.addMessage(if (other) "Incoming" else "My reply", i.toLong(), if (other) anna else me) }
        return sbn(NotificationCompat.Builder(context, "chat").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Me").setContentText("My reply").setStyle(style).build())
    }

    @Test fun ownRepliesCannotReappearThroughPersonOrAppFallback() {
        val own = chat(false)
        assertThat(person(own)).isNull()
        assertThat(app(own)).isNull()
    }

    @Test fun mixedConversationKeepsOnlyIncomingTextAndCount() {
        val entry = person(chat(true, false))!!
        assertThat(entry.name).isEqualTo("Anna")
        assertThat(entry.text).isEqualTo("Incoming")
        assertThat(entry.count).isEqualTo(1)
    }

    @Test fun mailKeepsCodeSubjectForBatchDecision() {
        val email = sbn(NotificationCompat.Builder(context, "mail").setCategory(Notification.CATEGORY_EMAIL)
            .setContentTitle("Sender").setContentText("Your verification code is 123456")
            .setStyle(NotificationCompat.BigTextStyle().bigText("This code expires in 10 minutes")).build())
        enableBatch()
        ReflectionHelpers.callInstanceMethod<Unit>(service, "holdForBatch", from(StatusBarNotification::class.java, email))
        assertThat(person(email)!!.title).isEqualTo("Your verification code is 123456")
        assertThat(ReflectionHelpers.getField<Map<String, Long>>(service, "heldUntil")).isEmpty()
    }

    @Test fun batchingDoesNotLoseHeadsUpSignalOrOnlyAlertOnceBookkeeping() {
        val pending = PendingIntent.getActivity(context, 0, Intent("test.open"), PendingIntent.FLAG_IMMUTABLE)
        val notification = sbn(NotificationCompat.Builder(context, "chat")
            .setCategory(Notification.CATEGORY_MESSAGE).setContentTitle("Anna").setContentText("Hello")
            .setFullScreenIntent(pending, true).setOnlyAlertOnce(true).build())
        enableBatch()
        post(notification)
        val first = service.badgeRepository.headsUp.value
        assertThat(first).isGreaterThan(0L)
        assertThat(service.badgeRepository.people.value.single().held).isTrue()
        assertThat(ReflectionHelpers.getField<Set<String>>(service, "alertedKeys")).contains(notification.key)
        service.onNotificationPosted(notification)
        assertThat(service.badgeRepository.headsUp.value).isEqualTo(first)
    }

    @Test fun sameKeyUpdateFromNewlyPinnedPersonIsDeliveredAndStaysActiveInAndroid() {
        enableBatch()
        val first = chat(true)
        post(first)
        assertThat(service.badgeRepository.people.value.single().held).isTrue()
        assertThat(service.badgeRepository.icons.value).isEmpty()
        assertThat(service.activeNotifications.map { it.key }).contains(first.key)
        ReflectionHelpers.setField(service, "vipKeys", setOf("anna"))
        // A pin alone leaves the old message waiting.
        ReflectionHelpers.callInstanceMethod<Unit>(service, "refresh")
        assertThat(service.badgeRepository.people.value.single().held).isTrue()
        post(chat(true, true))
        assertThat(service.badgeRepository.people.value.single().held).isFalse()
        assertThat(service.badgeRepository.icons.value).hasSize(1)
    }

    @Test fun expiredLocalHoldDeliversOnWakeWithoutAnotherNotification() {
        enableBatch()
        val first = chat(true)
        post(first)
        val held = ReflectionHelpers.getField<MutableMap<String, Long>>(service, "heldUntil")
        held[first.key] = System.currentTimeMillis() - 1
        ReflectionHelpers.getField<BroadcastReceiver>(service, "timeReceiver")
            .onReceive(context, Intent(Intent.ACTION_SCREEN_ON))
        assertThat(service.badgeRepository.people.value.single().held).isFalse()
        assertThat(service.badgeRepository.icons.value).hasSize(1)
        assertThat(held).isEmpty()
    }

    @Test fun packageChangeRefreshesMailClassificationWithoutReconnecting() = runTest {
        enableBatch()
        val notification = sbn(NotificationCompat.Builder(context, "mail")
            .setContentTitle("Sender").setContentText("Subject").build())
        assertThat(person(notification)).isNull()
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "chat.app"
                name = "MailActivity"
                applicationInfo = ApplicationInfo().apply { packageName = "chat.app" }
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:someone@example.com")), resolveInfo,
        )
        ReflectionHelpers.getField<BroadcastReceiver>(service, "packageReceiver")
            .onReceive(context, Intent(Intent.ACTION_PACKAGE_ADDED, Uri.parse("package:chat.app")))
        ReflectionHelpers.getField<Job>(service, "packageRefreshJob").join()
        assertThat(person(notification)!!.name).isEqualTo("Sender")
    }
}
