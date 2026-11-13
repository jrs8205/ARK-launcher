package org.arkikeskus.launcher.data

import android.content.IntentSender
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.arkikeskus.launcher.data.di.ApplicationScope
import org.arkikeskus.launcher.model.AppItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device's private space (Android 15 and later). [locked] is the profile's quiet mode as the system
 * reports it. [hiddenWhenLocked] says the entry to the space must not show while it is locked: Android's own
 * setting where it can be read (Android 16), the launcher's switch below that. [transitioning] is true from a
 * lock or unlock request until the system reports the change, at most a few seconds.
 */
data class PrivateSpace(
    val userSerial: Long,
    val locked: Boolean,
    val hiddenWhenLocked: Boolean,
    val transitioning: Boolean = false,
)

/**
 * The private space as one process-wide state. [state] is current from the moment it is first read (read once
 * in the constructor, then on every profile broadcast and every [refresh]); [apps] is what the app scan listed
 * for the space, and is empty whenever [state] is not an unlocked space, whatever the scan still holds: the two
 * are views of one value, so nobody can see the space locked and its apps still listed. Nothing here is written
 * anywhere: no usage, no label, no log line that names a private app.
 */
@Singleton
class PrivateSpaceRepository internal constructor(
    private val system: ProfileSystem,
    changes: Flow<Unit>,
    scannedApps: StateFlow<List<AppItem>>,
    private val start: (AppItem, Rect?, Bundle?) -> Result<Unit>,
    hidePreference: StateFlow<Boolean?>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val transitionTimeoutMs: Long,
) {
    /** Listens through [WorkProfileRepository.changes], the one profile receiver of the process. */
    @Inject constructor(
        system: ProfileSystem,
        work: WorkProfileRepository,
        source: LauncherAppsSource,
        settings: SettingsRepository,
        @ApplicationScope scope: CoroutineScope,
    ) : this(
        system = system,
        changes = work.changes,
        scannedApps = source.privateApps,
        start = source::launch,
        // Null until the stored settings are read.
        hidePreference = settings.settings.map<_, Boolean?> { it.hidePrivateSpaceWhenLocked }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, null),
        scope = scope,
        io = Dispatchers.IO,
        transitionTimeoutMs = TRANSITION_TIMEOUT_MS,
    )

    /** The platform's answer: the private profile (null: none), every serial typed private, every serial
     *  positively typed as a profile of the shared list (the own user, a work profile, a clone), and Android's
     *  own hide setting for the private profile (null where it cannot be read). */
    private data class Platform(
        val profile: ProfileSnapshot?,
        val privateSerials: Set<Long>,
        val sharedSerials: Set<Long>,
        val entryHidden: Boolean?,
        val installSender: IntentSender?,
        val settingsTarget: PrivateSettingsTarget?,
    )

    private fun read(): Platform {
        val profiles = system.snapshot()
        val profile = privateProfileOf(profiles)
        return Platform(
            profile = profile,
            privateSerials = profiles.filter { it.kind == ProfileKind.Private }.mapTo(HashSet()) { it.serial },
            sharedSerials = profiles
                .filter { it.kind == ProfileKind.Own || it.kind == ProfileKind.Managed || it.kind == ProfileKind.Clone }
                .mapTo(HashSet()) { it.serial },
            entryHidden = profile?.let { system.privateSpaceEntryHidden(it.serial) },
            // Asked for only when there is a private space; a button without a target is not shown.
            installSender = profile?.let { system.privateInstallSender(it.serial) },
            settingsTarget = profile?.let { system.privateSettingsTarget() },
        )
    }

    private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Read once right here (the first reader must not take a private profile for none while the scope spins
     *  up), then again on every profile broadcast and every [refresh]. */
    private val platform: StateFlow<Platform> = merge(changes, refreshes)
        .map { withContext(io) { read() } }
        .stateIn(scope, SharingStarted.Eagerly, initialValue = read())

    private val transitioning = MutableStateFlow(false)

    /** The state and the apps that go with it, decided together. */
    private data class View(val space: PrivateSpace?, val apps: List<AppItem>)

    private fun view(platform: Platform, scanned: List<AppItem>, hide: Boolean?, transitioning: Boolean): View {
        val profile = platform.profile ?: return View(null, emptyList())
        val space = PrivateSpace(
            userSerial = profile.serial,
            locked = profile.paused,
            // The system's own setting wins where it answers. A launcher switch that has not been read yet
            // counts as on: the entry may appear a moment late, never show where it was to stay hidden.
            hiddenWhenLocked = platform.entryHidden ?: hide ?: true,
            transitioning = transitioning,
        )
        return View(space, if (profile.paused) emptyList() else scanned)
    }

    private val view: StateFlow<View> = combine(platform, scannedApps, hidePreference, transitioning, ::view)
        .stateIn(scope, SharingStarted.Eagerly, view(platform.value, scannedApps.value, hidePreference.value, false))

    /** Null when the device has no private space (always below Android 15). */
    val state: StateFlow<PrivateSpace?> = MappedStateFlow(view) { it.space }

    /** The private apps, A to Z, while the space is unlocked; empty the moment [state] says locked or gone. */
    val apps: StateFlow<List<AppItem>> = MappedStateFlow(view) { it.apps }

    /** Whether [userSerial] is a private profile's: what such a serial names must leave no trace anywhere. */
    fun isPrivate(userSerial: Long): Boolean = userSerial in platform.value.privateSerials

    /**
     * Whether what belongs to [user] must leave no trace in the launcher: the rule for whoever holds a profile
     * rather than one of the launcher's own rows (a notification, a pin request). From Android 15 that is every
     * profile except the own user and one the platform positively typed as a work or clone profile: the private
     * space, and also a profile of unknown kind, one the last snapshot does not know, and one whose serial
     * number cannot be read, since any of those may be a private space the launcher was not told of. The same
     * rule as [listingOf], which lists such a profile's apps nowhere. Below Android 15 there is no private space.
     */
    fun mustLeaveNoTrace(user: UserHandle, userManager: UserManager?, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        if (sdkInt < Build.VERSION_CODES.VANILLA_ICE_CREAM || user == Process.myUserHandle()) return false
        val serial = runCatching { userManager?.getSerialNumberForUser(user) }.getOrNull()?.takeIf { it >= 0 }
        return serial == null || serial !in platform.value.sharedSerials
    }

    /** What opens an app store inside the private space, or null when there is none (no Install tile then). */
    fun installSender(): IntentSender? = platform.value.installSender

    /** What opens the private space's settings, or null when nothing does (no settings button then). */
    fun settingsTarget(): PrivateSettingsTarget? = platform.value.settingsTarget

    /** Reads Android's hide setting again: it has no broadcast, so the drawer asks when it opens. */
    fun refresh() {
        refreshes.tryEmit(Unit)
    }

    /**
     * Asks the system to lock ([locked] = true) or unlock the space. A `true` or `false` answer both mean the
     * system took it (`false` is its own credential prompt); the state follows the system's broadcast, and
     * until then, or [transitionTimeoutMs] should the user cancel the prompt, [PrivateSpace.transitioning] is set.
     */
    suspend fun setLocked(locked: Boolean): PauseOutcome {
        val serial = platform.value.profile?.serial ?: return PauseOutcome.Failed
        val failure = withContext(io) { runCatching { system.requestQuietMode(serial, locked) } }.exceptionOrNull()
        return when {
            failure is SecurityException -> PauseOutcome.NotDefaultLauncher
            failure != null -> PauseOutcome.Failed
            else -> {
                transitioning.value = true
                scope.launch {
                    withTimeoutOrNull(transitionTimeoutMs) { platform.first { it.profile?.paused == locked } }
                    transitioning.value = false
                }
                PauseOutcome.Requested
            }
        }
    }

    /** Starts a private app. Unlike [AppRepository.launch] it counts nothing: the usage ranking is written to
     *  the disk under the app's key. */
    fun launch(app: AppItem, sourceBounds: Rect? = null, options: Bundle? = null): Result<Unit> =
        start(app, sourceBounds, options)

    private companion object {
        const val TRANSITION_TIMEOUT_MS = 5_000L
    }
}

/**
 * A [StateFlow] that is a view of [source]: its value is computed from the source's value at the moment it is
 * read, so two views of one source never disagree, not even between two dispatches.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class MappedStateFlow<T, R>(private val source: StateFlow<T>, private val transform: (T) -> R) : StateFlow<R> {
    override val value: R get() = transform(source.value)
    override val replayCache: List<R> get() = listOf(value)
    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        source.map { transform(it) }.distinctUntilChanged().collect(collector)
        awaitCancellation()
    }
}
