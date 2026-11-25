package org.arkikeskus.launcher.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** A foreground home switch keeps its last complete frame until the destination arrives. */
internal fun homeSwitchFrame(active: Long?, next: HomeUiState, source: Long?, previous: HomeUiState?): HomeUiState? =
    next.takeIf { it.loaded && it.homeProfileId == active }
        ?: previous?.takeIf { source != null && active != null }

/** Keep the shell composed even without rows. Apply the supplied modifier to the home itself so
 * a switch is acknowledged only after that profile's home and its children have been laid out. */
@Composable
fun VisibleHomeFrame(viewModel: HomeViewModel, content: @Composable (HomeUiState?, Modifier) -> Unit) {
    // Keep the small, eagerly shared profile ID current behind Settings; the heavier rows may replay.
    val active by viewModel.activeHomeProfileId.collectAsState()
    val next by viewModel.uiState.collectAsStateWithLifecycle()
    val source by viewModel.homeSwitchSource.collectAsState()
    val finishedAt by viewModel.homeSwitchFinishedAt.collectAsState()
    VisibleHomeFrame(active, next, source, finishedAt, viewModel::endHomeSwitch, content)
}

@Composable
internal fun VisibleHomeFrame(
    active: Long?, next: HomeUiState, source: Long?, finishedAt: Long?,
    endSwitch: () -> Unit, content: @Composable (HomeUiState?, Modifier) -> Unit,
) {
    var previous by remember { mutableStateOf<HomeUiState?>(null) }
    LifecycleStartEffect(Unit) {
        onStopOrDispose {
            previous = null
            endSwitch()
        }
    }
    val frame = homeSwitchFrame(active, next, source, previous)
    val profile = frame?.homeProfileId
    var placed by remember(profile) { mutableStateOf(false) }
    val homeLayout = if (frame != null && !placed) Modifier.onGloballyPositioned {
        previous = frame
        placed = true
    } else Modifier
    if (frame == null || placed) {
        SideEffect {
            previous = frame
            if (finishedAt != null && finishedAt == profile && finishedAt == active) endSwitch()
        }
    }
    content(frame, homeLayout)
}

/**
 * Draws the home screen ([content]) of the home profile that is on, and nothing of any other.
 *
 * [active] is the profile that is on (null until it has been read), [uiState] the state the screen holds.
 * The two arrive apart, in either order, and [uiState] can be an old one: it is not followed while the
 * screen is not shown, so after a switch made in the launcher's Settings the screen comes back holding the
 * state of the profile that left. A loaded state of another profile than the one that is on is therefore
 * not drawn at all; the wallpaper shows for the moment until the two agree.
 *
 * [content] is told the profile it is drawn for: the one thing that stays the same for as long as it lives
 * (the state is a placeholder of no profile until the layout has been read), and so what the screen names
 * when it asks for a change.
 *
 * Everything [content] remembers belongs to one profile: an open folder, a menu, an edit frame, a widget
 * being added, the page the pager is on. With another profile it all starts anew, so the pager begins on
 * that profile's own start page. A change inside the profile, and the layout arriving at a start, reset
 * nothing.
 *
 * What the screen of a profile leaves behind in the Activity's widget host goes with it too: the host views
 * it made ([LauncherWidgetHost.forgetViews]).
 *
 * Until the profile has been read nothing is drawn either, not even for a guess. A screen first made for
 * the first profile and then for the one that is on started twice, and the first start had nothing of what
 * the real profile's screen saved before the process died: a widget being added was not pending for it, so
 * its start-up sweep could free that widget's id before the second start, which did have the saved state,
 * came to keep it.
 */
@Composable
internal fun HomeOfActiveProfile(active: Long?, uiState: HomeUiState, content: @Composable (profile: Long, HomeUiState) -> Unit) {
    if (active == null) return
    if (uiState.loaded && uiState.homeProfileId != active) return
    key(active) {
        // The widget views this profile's screen makes are registered with the Activity's host, which
        // outlives the screen: when the screen leaves (another profile's comes), the host lets go of them.
        val host = LocalAppWidgetHost.current as? LauncherWidgetHost
        DisposableEffect(host) { onDispose { host?.forgetViews() } }
        content(active, uiState)
    }
}
