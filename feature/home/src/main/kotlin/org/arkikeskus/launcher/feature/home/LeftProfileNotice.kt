package org.arkikeskus.launcher.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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
 */
@Composable
internal fun LeftProfileNotice(left: Flow<Long?>, show: (message: Int) -> Unit, shown: (profileId: Long) -> Unit) {
    val showNow by rememberUpdatedState(show)
    val shownNow by rememberUpdatedState(shown)
    LaunchedEffect(left) {
        left.collect { profileId ->
            if (profileId != null) {
                showNow(HomeNotice.ProfileNotRead.message)
                shownNow(profileId)
            }
        }
    }
}
