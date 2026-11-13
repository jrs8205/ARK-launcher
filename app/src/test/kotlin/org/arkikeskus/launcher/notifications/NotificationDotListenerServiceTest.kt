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
import android.os.UserHandle
import android.os.UserManager
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import coil3.ImageLoader
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.arkikeskus.launcher.data.AppLabelCache
import org.arkikeskus.launcher.data.AppLabelStore
import org.arkikeskus.launcher.data.IconPackRepository
import org.arkikeskus.launcher.data.LauncherAppsSource
import org.arkikeskus.launcher.data.NotificationBadgeRepository
import org.arkikeskus.launcher.data.PersonEntry
import org.arkikeskus.launcher.data.PrivateSpaceRepository
import org.arkikeskus.launcher.data.ProfileKind
import org.arkikeskus.launcher.data.ProfileSnapshot
import org.arkikeskus.launcher.data.ProfileSystem
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.WorkProfileRepository
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
import javax.inject.Provider

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
        service.privateSpace = privateSpace(own)
    }

    @After fun tearDown() {
        service.onDestroy()
        repositoryScope.cancel()
        Dispatchers.resetMain()
    }

    private val own = ProfileSnapshot(0, ProfileKind.Own, badged = false, paused = false)
    private val repositoryScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())

    /** The platform's profiles as a test steers them: [list] is what a snapshot answers, [changed] a broadcast. */
    private class Profiles(var list: List<ProfileSnapshot>) : ProfileSystem {
        val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        override fun snapshot(): List<ProfileSnapshot> = list
        override fun changes(): Flow<Unit> = changed
        override fun requestQuietMode(serial: Long, enabled: Boolean): Boolean = error("unused")
        override fun privateSpaceEntryHidden(serial: Long): Boolean? = null
        override fun privateInstallSender(serial: Long): android.content.IntentSender? = null
        override fun privateSettingsTarget(): org.arkikeskus.launcher.data.PrivateSettingsTarget? = null
    }

    /** The private space repository of a device with [profiles] (it reads them once, when it is made). */
    private fun privateSpace(vararg profiles: ProfileSnapshot): PrivateSpaceRepository = privateSpace(Profiles(profiles.toList()))

    private fun privateSpace(system: ProfileSystem): PrivateSpaceRepository {
        val labels = AppLabelCache(object : AppLabelStore {
            override suspend fun read(locale: String): Map<String, String> = emptyMap()
            override suspend fun write(locale: String, labels: Map<String, String>) = Unit
        })
        val source = LauncherAppsSource(context, IconPackRepository(context), Provider<ImageLoader> { error("unused") }, labels, system)
        return PrivateSpaceRepository(system, WorkProfileRepository(system, repositoryScope), source, service.settingsRepository, repositoryScope)
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

    // The private space. Its notifications are not read at all: no dot, no status icon, no row in the
    // notifications widget, no people tile, and above all no key in the batch delivery's stored map.

    private val privateUser: UserHandle = UserHandle.getUserHandleForUid(11 * 100_000)

    private fun aDeviceWithAPrivateSpace() =
        aDeviceWith(ProfileSnapshot(11, ProfileKind.Private, badged = false, paused = false))

    /** The own user and [others], each a profile the user manager answers for, as the platform types them. */
    private fun aDeviceWith(vararg others: ProfileSnapshot) {
        val users = shadowOf(context.getSystemService(UserManager::class.java))
        users.addUser(0, "Owner", 0)
        others.forEach { users.addProfile(0, it.serial.toInt(), "Profile ${it.serial}", 0) }
        service.privateSpace = privateSpace(own, *others)
        ReflectionHelpers.setField(service, "connected", true)
        ReflectionHelpers.setField(service, "heldLoaded", true)
        ReflectionHelpers.setField(service, "settingsReady", true)
    }

    /** An incoming message of chat.app, the app of the personal [chat], posted in the profile of [user]. */
    private fun chatOf(user: UserHandle): StatusBarNotification {
        val style = NotificationCompat.MessagingStyle(me).addMessage("A private word", 1L, anna)
        val notification = NotificationCompat.Builder(context, "chat").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setCategory(Notification.CATEGORY_MESSAGE).setContentTitle("Anna").setContentText("A private word")
            .setStyle(style).build()
        return StatusBarNotification(
            "chat.app", "chat.app", 7, null, user.hashCode() * 100_000 + 1000, 0, 0, notification, user, System.currentTimeMillis(),
        )
    }

    private fun held(): Map<String, Long> = ReflectionHelpers.getField(service, "heldUntil")

    @Config(sdk = [35])
    @Test fun aPrivateSpacesNotificationIsNotRead() {
        aDeviceWithAPrivateSpace()
        val private = chatOf(privateUser)

        post(private)

        assertThat(service.badgeRepository.badges.value).isEmpty()
        assertThat(service.badgeRepository.icons.value).isEmpty()
        assertThat(service.badgeRepository.people.value).isEmpty()

        service.onNotificationRemoved(private, null, 0)
        assertThat(service.badgeRepository.people.value).isEmpty()
    }

    @Config(sdk = [35])
    @Test fun thePersonalTwinOfAPrivateAppIsReadAsBefore() {
        aDeviceWithAPrivateSpace()

        post(chatOf(privateUser))
        post(chat(true))

        assertThat(service.badgeRepository.badges.value).containsExactly("chat.app/0", 1)
        assertThat(service.badgeRepository.icons.value.map { it.userSerial }).containsExactly(0L)
        assertThat(service.badgeRepository.people.value).hasSize(1)
    }

    @Config(sdk = [35])
    @Test fun aPrivateNotificationIsNeverHeldForBatchDeliveryOrStored() = runTest {
        aDeviceWithAPrivateSpace()
        enableBatch()

        post(chatOf(privateUser))

        assertThat(held()).isEmpty()
        assertThat(service.settingsRepository.heldNotifications.first()).isEmpty()

        // The same message of the personal app waits and is stored, as before.
        val personal = chat(true)
        post(personal)
        assertThat(held().keys).containsExactly(personal.key)
        assertThat(service.settingsRepository.heldNotifications.first().keys).containsExactly(personal.key)
    }

    @Config(sdk = [35])
    @Test fun aPrivateKeyAnOlderVersionStoredIsForgottenAtOnce() = runTest {
        aDeviceWithAPrivateSpace()
        enableBatch()
        // Before this version a private message was held like any other, and its key written to the settings.
        val private = chatOf(privateUser)
        val later = System.currentTimeMillis() + 3_600_000
        service.settingsRepository.setHeldNotifications(mapOf(private.key to later))
        ReflectionHelpers.getField<MutableMap<String, Long>>(service, "heldUntil")[private.key] = later
        Shadow.extract<ShadowNotificationListenerService>(service).addActiveNotification(private)

        ReflectionHelpers.callInstanceMethod<Unit>(service, "refresh")

        assertThat(held()).isEmpty()
        assertThat(service.settingsRepository.heldNotifications.first()).isEmpty()
        assertThat(service.badgeRepository.people.value).isEmpty()
    }

    @Config(sdk = [35])
    @Test fun aNotificationOfAProfileOfUnknownKindIsNotReadEither() {
        // Its serial reads fine, but the platform does not say what it is: it may be a private space.
        aDeviceWith(ProfileSnapshot(12, ProfileKind.Unknown, badged = true, paused = false))

        post(chatOf(UserHandle.getUserHandleForUid(12 * 100_000)))

        assertThat(service.badgeRepository.badges.value).isEmpty()
        assertThat(service.badgeRepository.icons.value).isEmpty()
        assertThat(service.badgeRepository.people.value).isEmpty()
    }

    /** The stores and the profile read answer from their own threads: waits, bounded, for [condition]. */
    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(20)
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
    }

    @Config(sdk = [35])
    @Test fun aNewWorkProfilesNotificationIsReadOnceTheProfileIsKnownForOne() {
        // A work profile was added a moment ago: Android can deliver its first notification before the launcher
        // has read what the profile is.
        val users = shadowOf(context.getSystemService(UserManager::class.java))
        users.addUser(0, "Owner", 0)
        users.addProfile(0, 10, "Work", 0)
        val profiles = Profiles(listOf(own))
        service.privateSpace = privateSpace(profiles)
        service.onListenerConnected()
        val fromWork = chatOf(UserHandle.getUserHandleForUid(10 * 100_000))

        post(fromWork)
        // Not placed yet, so left alone: it might have been a private space.
        assertThat(service.badgeRepository.badges.value).isEmpty()
        assertThat(service.badgeRepository.people.value).isEmpty()

        // The launcher has now read the new profile: a work profile.
        profiles.list = listOf(own, ProfileSnapshot(10, ProfileKind.Managed, badged = false, paused = false))
        profiles.changed.tryEmit(Unit)

        // Nothing new is posted: the notification that waited is read because the profile's kind is known.
        await("the work notification") { service.badgeRepository.badges.value == mapOf("chat.app/10" to 1) }
        assertThat(service.badgeRepository.people.value).hasSize(1)
    }

    @Config(sdk = [35])
    @Test fun aNewWorkProfilesMessageThatWaitedIsHeldForBatchDeliveryLikeAnyOther() = runTest {
        val users = shadowOf(context.getSystemService(UserManager::class.java))
        users.addUser(0, "Owner", 0)
        users.addProfile(0, 10, "Work", 0)
        val profiles = Profiles(listOf(own))
        service.privateSpace = privateSpace(profiles)
        service.onListenerConnected()
        enableBatch()
        val fromWork = chatOf(UserHandle.getUserHandleForUid(10 * 100_000))

        // Posted while the profile is unplaced: left alone, so the batch decision of a post is not made either.
        post(fromWork)
        assertThat(held()).isEmpty()

        profiles.list = listOf(own, ProfileSnapshot(10, ProfileKind.Managed, badged = false, paused = false))
        profiles.changed.tryEmit(Unit)

        // Known for a work profile: the message gets the decision it missed, and waits for the delivery time. It
        // must not show at once in the dots, the status bar and the widgets only because its profile was new.
        await("the work message as a waiting tile") { service.badgeRepository.people.value.size == 1 }
        assertThat(service.badgeRepository.people.value.single().held).isTrue()
        assertThat(held().keys).containsExactly(fromWork.key)
        assertThat(service.badgeRepository.badges.value).isEmpty()
        assertThat(service.badgeRepository.icons.value).isEmpty()
        assertThat(service.settingsRepository.heldNotifications.first().keys).containsExactly(fromWork.key)
    }

    @Config(sdk = [35])
    @Test fun aNotificationThatWasReadAtItsPostIsNotDecidedAgainWhenTheProfilesChange() = runTest {
        // A personal message already delivered (its hold was released): a profile appearing elsewhere must not
        // put it back to waiting.
        val users = shadowOf(context.getSystemService(UserManager::class.java))
        users.addUser(0, "Owner", 0)
        users.addProfile(0, 10, "Work", 0)
        val profiles = Profiles(listOf(own))
        service.privateSpace = privateSpace(profiles)
        service.onListenerConnected()
        enableBatch()
        val personal = chat(true)
        post(personal)
        assertThat(held().keys).containsExactly(personal.key)
        ReflectionHelpers.getField<MutableMap<String, Long>>(service, "heldUntil").remove(personal.key) // delivered
        ReflectionHelpers.callInstanceMethod<Unit>(service, "refresh")
        assertThat(service.badgeRepository.badges.value).containsExactly("chat.app/0", 1)

        profiles.list = listOf(own, ProfileSnapshot(10, ProfileKind.Managed, badged = false, paused = false))
        profiles.changed.tryEmit(Unit)
        await("the work profile known") { !service.privateSpace.mustLeaveNoTrace(UserHandle.getUserHandleForUid(10 * 100_000), context.getSystemService(UserManager::class.java)) }
        repeat(10) { Thread.sleep(20); shadowOf(android.os.Looper.getMainLooper()).idle() }

        assertThat(held()).isEmpty()
        assertThat(service.badgeRepository.badges.value).containsExactly("chat.app/0", 1)
    }

    @Config(sdk = [35])
    @Test fun aNewPrivateSpacesNotificationStaysUnreadWhenTheProfileIsKnown() {
        val users = shadowOf(context.getSystemService(UserManager::class.java))
        users.addUser(0, "Owner", 0)
        users.addProfile(0, 11, "Private", 0)
        val profiles = Profiles(listOf(own))
        service.privateSpace = privateSpace(profiles)
        service.onListenerConnected()
        post(chatOf(privateUser))
        post(chat(true)) // and a personal one, to see the re-read happen

        profiles.list = listOf(own, ProfileSnapshot(11, ProfileKind.Private, badged = false, paused = false))
        profiles.changed.tryEmit(Unit)
        await("the private profile known") { service.privateSpace.isPrivate(11) }
        repeat(10) { Thread.sleep(20); shadowOf(android.os.Looper.getMainLooper()).idle() }

        assertThat(service.badgeRepository.badges.value).containsExactly("chat.app/0", 1)
        assertThat(service.badgeRepository.people.value).hasSize(1)
    }

    @Config(sdk = [35])
    @Test fun aWorkProfilesNotificationIsReadAsBefore() {
        aDeviceWith(ProfileSnapshot(10, ProfileKind.Managed, badged = false, paused = false))

        post(chatOf(UserHandle.getUserHandleForUid(10 * 100_000)))

        assertThat(service.badgeRepository.badges.value).containsExactly("chat.app/10", 1)
        assertThat(service.badgeRepository.people.value).hasSize(1)
    }

    @Config(sdk = [35])
    @Test fun aRemovedPrivateNotificationTakesAKeyAnOlderVersionStoredAlong() = runTest {
        aDeviceWithAPrivateSpace()
        enableBatch()
        val private = chatOf(privateUser)
        val later = System.currentTimeMillis() + 3_600_000
        service.settingsRepository.setHeldNotifications(mapOf(private.key to later))
        ReflectionHelpers.getField<MutableMap<String, Long>>(service, "heldUntil")[private.key] = later

        // Dismissed before any refresh saw it: it is not active any more, so only its removal can forget the key.
        service.onNotificationRemoved(private, null, 0)

        assertThat(held()).isEmpty()
        assertThat(service.settingsRepository.heldNotifications.first()).isEmpty()
    }

    @Config(sdk = [35])
    @Test fun aProfileWhoseSerialCannotBeReadCountsAsPrivate() {
        aDeviceWithAPrivateSpace()
        // A profile the user manager does not answer for: it may be a private space the launcher was not told of.
        val unknown = UserHandle.getUserHandleForUid(12 * 100_000)

        post(chatOf(unknown))

        assertThat(service.badgeRepository.badges.value).isEmpty()
        assertThat(service.badgeRepository.icons.value).isEmpty()
        assertThat(service.badgeRepository.people.value).isEmpty()
    }

    /** The shape a community post takes on Android 16+: a big picture offloaded to a content URI,
     *  null picture / large-icon keys, two plain actions, no category. */
    private fun bigPicture(title: String, text: String, id: Int): StatusBarNotification {
        val open = PendingIntent.getActivity(context, id, Intent("test.open"), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "notifications")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text).setAutoCancel(true).setContentIntent(open)
            .addAction(0, "Go to community", open).addAction(0, "Turn off updates", open)
            .setStyle(NotificationCompat.BigPictureStyle()
                .bigPicture(android.graphics.drawable.Icon.createWithContentUri("content://com.android.bitmapoffload/bitmaps/$id")))
            .build()
        notification.extras.putParcelable(Notification.EXTRA_PICTURE, null)
        notification.extras.putParcelable(Notification.EXTRA_LARGE_ICON, null)
        return StatusBarNotification(
            "news.app", "news.app", 0, "tag-$id", 1000, 0, 0, notification, Process.myUserHandle(), System.currentTimeMillis(),
        )
    }

    @Test fun bigPictureAppNotificationsBecomeOneAppTile() {
        ReflectionHelpers.setField(service, "connected", true)
        ReflectionHelpers.setField(service, "heldLoaded", true)
        ReflectionHelpers.setField(service, "settingsReady", true)
        val first = bigPicture("Now in r/SEO", "Why does the favicon not show?", 1)
        val second = bigPicture("Now in r/android_beta", "Crash when entering the pin code", 2)
        assertThat(person(first)).isNull()
        assertThat(app(first)!!.title).isEqualTo("Now in r/SEO")
        post(first)
        post(second)
        val tile = service.badgeRepository.people.value.single()
        assertThat(tile.personKey).isEqualTo("app:news.app/0")
        assertThat(tile.count).isEqualTo(2)
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
