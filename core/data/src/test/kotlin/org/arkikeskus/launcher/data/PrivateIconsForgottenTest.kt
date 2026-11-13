package org.arkikeskus.launcher.data

import android.app.Application
import android.graphics.Bitmap
import android.os.UserHandle
import android.os.UserManager
import coil3.ImageLoader
import coil3.asImage
import coil3.memory.MemoryCache
import coil3.request.Options
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.IconRequest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.inject.Provider

/**
 * A private app's icon is drawn while the space is unlocked, so the image loader's memory holds it (it is
 * never on the disk: an icon request's result is memory only). When the app leaves the private list (the
 * space locks, the app is removed) the icon leaves that memory too, in every look it was cached in. The
 * personal apps' icons stay.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PrivateIconsForgottenTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val second: UserHandle = UserHandle.getUserHandleForUid(10 * 100_000)
    private val own = ProfileSnapshot(0, ProfileKind.Own, badged = false, paused = false)
    private fun space(locked: Boolean) = ProfileSnapshot(10, ProfileKind.Private, badged = false, paused = locked)

    private suspend fun await(condition: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(10_000) { while (!condition()) delay(10) }
    }

    @Test fun anUpdatedPrivateAppsCachedIconsGoWhileItStaysOnTheList() = runTest {
        val users = shadowOf(context.getSystemService(UserManager::class.java))
        users.addUser(0, "Owner", 0)
        users.addProfile(0, 10, "Second", 0)
        installFakeApp(context, "com.mail", "Mail")
        installFakeApp(context, "com.secret.bank", "Secret Bank", second)
        val loader = ImageLoader.Builder(context).memoryCache { MemoryCache.Builder().maxSizeBytes(1_000_000).build() }.build()
        val memory = loader.memoryCache!!
        val profiles = FakeProfileSystem(listOf(own, space(locked = false)))
        val shared = MutableStateFlow<List<AppItem>>(emptyList())
        val source = LauncherAppsSource(context, IconPackRepository(context), Provider { loader }, AppLabelCache(InMemoryAppLabelStore()), profiles)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.appsFlow().collect { shared.value = it } }
        await { source.privateApps.value.isNotEmpty() && shared.value.isNotEmpty() }
        val bank = source.privateApps.value.single()
        val mail = shared.value.single()
        val keyer = AppIconKeyer()
        val options = Options(context)
        fun key(request: IconRequest) = MemoryCache.Key(keyer.key(request, options))
        val icon = MemoryCache.Value(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImage())
        val bankKeys = listOf(key(IconRequest(bank)), key(IconRequest(bank, themed = true, dark = true)))
        val mailKey = key(IconRequest(mail))
        (bankKeys + mailKey).forEach { memory[it] = icon }

        // The private app is updated (a new icon, the same name and activity): it stays on the list.
        val registered: List<android.util.Pair<android.content.pm.LauncherApps.Callback, android.os.Handler>> =
            org.robolectric.util.ReflectionHelpers.getStaticField(org.robolectric.shadows.ShadowLauncherApps::class.java, "callbacks")
        registered.forEach { entry -> entry.second.post { entry.first.onPackageChanged("com.secret.bank", second) } }
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertThat(bankKeys.map { memory[it] }).containsExactly(null, null)
        assertThat(memory[mailKey]).isNotNull()
        assertThat(source.privateApps.value.map { it.packageName }).containsExactly("com.secret.bank")
    }

    @Test fun aLockedSpacesIconsLeaveTheImageLoadersMemory() = runTest {
        val users = shadowOf(context.getSystemService(UserManager::class.java))
        users.addUser(0, "Owner", 0)
        users.addProfile(0, 10, "Second", 0)
        installFakeApp(context, "com.mail", "Mail")
        installFakeApp(context, "com.secret.bank", "Secret Bank", second)
        val loader = ImageLoader.Builder(context).memoryCache { MemoryCache.Builder().maxSizeBytes(1_000_000).build() }.build()
        val memory = loader.memoryCache!!
        val profiles = FakeProfileSystem(listOf(own, space(locked = false)))
        val reload = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val shared = MutableStateFlow<List<AppItem>>(emptyList())
        val source = LauncherAppsSource(context, IconPackRepository(context), Provider { loader }, AppLabelCache(InMemoryAppLabelStore()), profiles)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.appsFlow(reload).collect { shared.value = it } }
        await { source.privateApps.value.isNotEmpty() && shared.value.isNotEmpty() }
        val bank = source.privateApps.value.single()
        val mail = shared.value.single()

        // What drawing the two apps leaves in the memory: one entry per look an icon was asked in.
        val keyer = AppIconKeyer()
        val options = Options(context)
        fun key(request: IconRequest) = MemoryCache.Key(keyer.key(request, options))
        val icon = MemoryCache.Value(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImage())
        val bankKeys = listOf(key(IconRequest(bank)), key(IconRequest(bank, themed = true, dark = true)), key(IconRequest(bank, epoch = 3)))
        val mailKey = key(IconRequest(mail))
        (bankKeys + mailKey).forEach { memory[it] = icon }

        profiles.profiles = listOf(own, space(locked = true))
        reload.emit(Unit)
        await { source.privateApps.value.isEmpty() && bankKeys.none { memory[it] != null } }

        assertThat(bankKeys.map { memory[it] }).containsExactly(null, null, null)
        assertThat(memory[mailKey]).isNotNull()
    }
}
