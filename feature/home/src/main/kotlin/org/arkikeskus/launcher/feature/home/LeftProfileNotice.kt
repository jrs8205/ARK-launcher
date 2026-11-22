package org.arkikeskus.launcher.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import org.arkikeskus.launcher.ui.HomeNotice

/**
 * Tells the user that a home profile could not be opened and another one is shown: [left] names the profile
 * for as long as that is owed ([HomeViewModel.leftHomeProfile]); [show] puts a text on screen; [shown] is
 * told once it has been.
 *
 * The screen says that the notice was shown, and after showing it. Said by the view model as it queued the
 * notice for a screen, it was "told" with no screen there to show it (the home activity destroyed before its
 * first frame), and the user found another home screen than the one that was on, with no word of why.
 *
 * And it is shown only while the home screen is what the user SEES: the home activity resumed, and nothing of
 * the launcher's own over the home screen ([onScreen]: the app drawer is not open or sliding). The home
 * screen is part of the launcher's window under the drawer too, and stays composed while the home activity
 * is behind Settings: a profile left then was told over the drawer or over Settings, and the home screen the
 * user came back to said nothing. Until then the notice is owed.
 */
@Composable
internal fun LeftProfileNotice(
    left: Flow<Long?>,
    onScreen: Boolean,
    show: (message: Int) -> Unit,
    shown: (profileId: Long) -> Unit,
) {
    val showNow by rememberUpdatedState(show)
    val shownNow by rememberUpdatedState(shown)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(left, lifecycle, onScreen) {
        if (!onScreen) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            left.collect { profileId ->
                if (profileId != null) {
                    showNow(HomeNotice.ProfileNotRead.message)
                    shownNow(profileId)
                }
            }
        }
    }
}
