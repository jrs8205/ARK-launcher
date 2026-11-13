package org.arkikeskus.launcher.data

import android.app.Application
import android.os.UserHandle
import android.os.UserManager
import coil3.ImageLoader
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.arkikeskus.launcher.model.AppItem
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.inject.Provider

/**
 * The private space's apps are split off where the profiles are listed: the shared list never holds one, the label
 * cache never hears of one, and the private list exists only while the space is unlocked. A profile the launcher
 * cannot tell the kind of is listed nowhere.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LauncherAppsSourcePrivateSplitTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val second: UserHandle = UserHandle.getUserHandleForUid(10 * 100_000)
    private val own = ProfileSnapshot(0, ProfileKind.Own, badged = false, paused = false)
    private fun second(kind: ProfileKind, paused: Boolean = false) = ProfileSnapshot(10, kind, badged = false, paused = paused)

    private val store = InMemoryAppLabelStore()
    private val emissions = MutableStateFlow<List<List<AppItem>>>(emptyList())
    private lateinit var source: LauncherAppsSource

    @Before fun twoUsersEachWithAnApp() {
        val users = shadowOf(context.getSystemService(UserManager::class.java))
        users.addUser(0, "Owner", 0)
        users.addProfile(0, 10, "Second", 0)
        installFakeApp(context, "com.mail", "Mail")
        installFakeApp(context, "com.secret.bank", "Secret Bank", second)
    }

    /** One scan with the second profile as [profile] says, to its end (the label cache's write). */
    private suspend fun TestScope.scan(profile: ProfileSnapshot?) {
        val profiles = FakeProfileSystem(listOfNotNull(own, profile))
        source = LauncherAppsSource(
            context, IconPackRepository(context), Provider<ImageLoader> { error("unused") }, AppLabelCache(store), profiles,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.appsFlow().collect { apps -> emissions.update { it + listOf(apps) } }
        }
        // The scan runs on a real IO thread: wait in real time for its emission and for the write that ends it.
        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                emissions.first { it.isNotEmpty() }
                while (store.writes < 1) kotlinx.coroutines.delay(10)
            }
        }
    }

    private fun shared(): List<String> = emissions.value.last().map { it.packageName }
    private fun cachedKeys(): Set<String> = store.stored.values.flatMap { it.keys }.toSet()

    @Test fun anUnlockedPrivateSpacesAppIsOnItsOwnListAndNowhereElse() = runTest {
        scan(second(ProfileKind.Private))

        assertThat(shared()).containsExactly("com.mail")
        val private = source.privateApps.value
        assertThat(private.map { it.packageName }).containsExactly("com.secret.bank")
        assertThat(private.single().userSerial).isEqualTo(10L)
        assertThat(private.single().user).isEqualTo(second)
        // The label cache, which is written to the disk, never heard of it.
        assertThat(cachedKeys().none { "com.secret.bank" in it }).isTrue()
        assertThat(cachedKeys()).containsExactly("com.mail/com.mail.Main/0")
    }

    @Test fun aLockedPrivateSpaceHasNoList() = runTest {
        scan(second(ProfileKind.Private, paused = true))

        assertThat(shared()).containsExactly("com.mail")
        assertThat(source.privateApps.value).isEmpty()
        assertThat(cachedKeys().none { "com.secret.bank" in it }).isTrue()
    }

    @Test fun aWorkProfilesAppIsInTheSharedListAsBefore() = runTest {
        scan(second(ProfileKind.Managed))

        assertThat(shared()).containsExactly("com.mail", "com.secret.bank")
        assertThat(source.privateApps.value).isEmpty()
        assertThat(cachedKeys()).containsExactly("com.mail/com.mail.Main/0", "com.secret.bank/com.secret.bank.Main/10")
    }

    @Test fun aProfileOfUnknownKindIsListedNowhere() = runTest {
        scan(second(ProfileKind.Unknown))

        assertThat(shared()).containsExactly("com.mail")
        assertThat(source.privateApps.value).isEmpty()
        assertThat(cachedKeys().none { "com.secret.bank" in it }).isTrue()
    }

    @Test fun aProfileTheSnapshotDoesNotKnowIsListedNowhere() = runTest {
        scan(profile = null)

        assertThat(shared()).containsExactly("com.mail")
        assertThat(source.privateApps.value).isEmpty()
    }
}
