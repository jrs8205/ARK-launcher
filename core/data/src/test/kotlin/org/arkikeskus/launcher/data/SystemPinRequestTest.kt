package org.arkikeskus.launcher.data

import android.app.Application
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.Proxy

/**
 * The confirmation is an exported activity, so any app can start it with a request of its own making
 * instead of the system's: what the request says about a widget is checked against the system.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemPinRequestTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val nobodyPrivate: (UserHandle) -> Boolean = { false }

    /** [label] and the profile come from the provider's activity info (a hidden field): the profile is [uid]'s. */
    private fun provider(component: ComponentName, label: String, minWidth: Int = 0, uid: Int = Process.myUid()) = AppWidgetProviderInfo().also {
        it.provider = component
        it.minWidth = minWidth
        val activity = ActivityInfo().apply {
            packageName = component.packageName
            name = component.className
            nonLocalizedLabel = label
            applicationInfo = ApplicationInfo().apply {
                packageName = component.packageName
                this.uid = uid
            }
        }
        AppWidgetProviderInfo::class.java.getField("providerInfo").set(it, activity)
    }

    private fun install(info: AppWidgetProviderInfo) =
        shadowOf(AppWidgetManager.getInstance(context)).addInstalledProvidersForProfile(Process.myUserHandle(), info)

    /** The system's own request class (its hidden constructor) around an inner object an app made up,
     *  describing [info]: what another app could start the confirmation with. */
    private fun madeUpWidgetRequest(info: AppWidgetProviderInfo): Intent {
        val innerClass = Class.forName("android.content.pm.IPinItemRequest")
        val inner = Proxy.newProxyInstance(innerClass.classLoader, arrayOf(innerClass)) { _, method, _ ->
            when (method.name) {
                "isValid", "accept" -> true
                "getAppWidgetProviderInfo" -> info
                "getExtras" -> Bundle()
                else -> null
            }
        }
        val request = LauncherApps.PinItemRequest::class.java
            .getDeclaredConstructor(innerClass, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(inner, LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET)
        return Intent(LauncherApps.ACTION_CONFIRM_PIN_APPWIDGET).putExtra(LauncherApps.EXTRA_PIN_ITEM_REQUEST, request)
    }

    private val clock = ComponentName("com.clock", "com.clock.Analog")

    @Test
    fun aWidgetRequestNamingNoInstalledProviderIsDismissed() {
        assertThat(systemPinRequest(context, madeUpWidgetRequest(provider(clock, "Analog clock")), nobodyPrivate)).isNull()
    }

    @Test
    fun aWidgetIsDescribedByItsInstalledProviderNotByTheRequest() {
        install(provider(clock, "Analog clock", minWidth = 250))

        val request = systemPinRequest(context, madeUpWidgetRequest(provider(clock, "Your bank", minWidth = 1)), nobodyPrivate) as WidgetPinRequest

        assertThat(request.item).isEqualTo(PinItem.Widget(clock.flattenToString(), "Analog clock", ownProfile = true))
        assertThat(request.info.minWidth).isEqualTo(250)
    }

    /** [shortcut] in a request of the same made-up kind. */
    private fun madeUpShortcutRequest(shortcut: ShortcutInfo): Intent {
        val innerClass = Class.forName("android.content.pm.IPinItemRequest")
        val inner = Proxy.newProxyInstance(innerClass.classLoader, arrayOf(innerClass)) { _, method, _ ->
            when (method.name) {
                "isValid", "accept" -> true
                "getShortcutInfo" -> shortcut
                else -> null
            }
        }
        val request = LauncherApps.PinItemRequest::class.java
            .getDeclaredConstructor(innerClass, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(inner, LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT)
        return Intent(LauncherApps.ACTION_CONFIRM_PIN_SHORTCUT).putExtra(LauncherApps.EXTRA_PIN_ITEM_REQUEST, request)
    }

    /** A shortcut the system has (published, or pinned already) shows as the system has it, whatever
     *  name a request gives it. */
    @Test
    fun aShortcutTheSystemHasIsShownAsTheSystemHasIt() {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        shadowOf(launcherApps).addDynamicShortcut(ShortcutInfo.Builder(context, "mia").setShortLabel("Mia").build())

        val request = systemPinRequest(context, madeUpShortcutRequest(ShortcutInfo.Builder(context, "mia").setShortLabel("Your bank").build()), nobodyPrivate)

        assertThat((request!!.item as PinItem.Shortcut).label).isEqualTo("Mia")
    }

    /** One only the request carries (a browser's page, say) shows as requested: it is stored only if the
     *  system pins it (PinRequestStoreTest). */
    @Test
    fun aShortcutOnlyTheRequestCarriesShowsAsRequested() {
        val request = systemPinRequest(context, madeUpShortcutRequest(ShortcutInfo.Builder(context, "page").setShortLabel("A page").build()), nobodyPrivate)

        assertThat((request!!.item as PinItem.Shortcut).label).isEqualTo("A page")
    }

    // The private space. A pinned shortcut or widget is a home row, written to the database: a request from
    // the private profile is dismissed like one that cannot be read, before anything of it is looked up.

    private val workUser: UserHandle = UserHandle.getUserHandleForUid(10 * 100_000)
    private val privateUser: UserHandle = UserHandle.getUserHandleForUid(11 * 100_000)
    private val thePrivateSpace: (UserHandle) -> Boolean = { it == privateUser }

    private fun twoProfiles() {
        val users = shadowOf(context.getSystemService(UserManager::class.java))
        users.addUser(0, "Owner", 0)
        users.addProfile(0, 10, "Work", 0)
        users.addProfile(0, 11, "Private", 0)
    }

    /** A widget of [user]'s profile, installed there, and the request for it. */
    private fun widgetRequestOf(user: UserHandle, userId: Int): Intent {
        val info = provider(clock, "Analog clock", uid = userId * 100_000 + 10_123)
        shadowOf(AppWidgetManager.getInstance(context)).addInstalledProvidersForProfile(user, info)
        return madeUpWidgetRequest(info)
    }

    /** A request for a shortcut of the profile with [userId] (a hidden field of the shortcut). */
    private fun shortcutRequestOf(userId: Int): Intent {
        val shortcut = ShortcutInfo.Builder(context, "page").setShortLabel("A page").build()
        ReflectionHelpers.setField(shortcut, "mUserId", userId)
        return madeUpShortcutRequest(shortcut)
    }

    @Test
    fun aWidgetRequestFromThePrivateSpaceIsDismissed() {
        twoProfiles()
        val intent = widgetRequestOf(privateUser, 11)

        assertThat(systemPinRequest(context, intent, thePrivateSpace)).isNull()
        // The request itself is a good one: it is the profile that dismisses it.
        assertThat(systemPinRequest(context, intent, nobodyPrivate)).isNotNull()
    }

    @Test
    fun aShortcutRequestFromThePrivateSpaceIsDismissed() {
        twoProfiles()
        val intent = shortcutRequestOf(11)

        assertThat(systemPinRequest(context, intent, thePrivateSpace)).isNull()
        assertThat(systemPinRequest(context, intent, nobodyPrivate)).isNotNull()
    }

    @Test
    fun aWorkProfilesWidgetIsOfferedAsBefore() {
        twoProfiles()

        val request = systemPinRequest(context, widgetRequestOf(workUser, 10), thePrivateSpace) as WidgetPinRequest

        // Not the own profile: the confirmation tells the user it cannot be added (PinDecision.WorkProfile).
        assertThat(request.item).isEqualTo(PinItem.Widget(clock.flattenToString(), "Analog clock", ownProfile = false))
    }

    @Test
    fun aWorkProfilesShortcutIsOfferedAsBefore() {
        twoProfiles()

        val request = systemPinRequest(context, shortcutRequestOf(10), thePrivateSpace)

        assertThat((request!!.item as PinItem.Shortcut).userSerial).isEqualTo(10L)
    }
}
