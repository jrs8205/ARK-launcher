package org.arkikeskus.launcher.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key

/**
 * Draws the home screen ([content]) of the home profile that is on, and nothing of any other.
 *
 * [active] is the profile that is on (null until it has been read), [uiState] the state the screen holds.
 * The two arrive apart, in either order, and [uiState] can be an old one: it is not followed while the
 * screen is not shown, so after a switch made in the launcher's Settings the screen comes back holding the
 * state of the profile that left. A loaded state of another profile than the one that is on is therefore
 * not drawn at all; the wallpaper shows for the moment until the two agree.
 *
 * Everything [content] remembers belongs to one profile: an open folder, a menu, an edit frame, a widget
 * being added, the page the pager is on. With another profile it all starts anew, so the pager begins on
 * that profile's own start page. A change inside the profile, and the layout arriving at a start, reset
 * nothing.
 */
@Composable
internal fun HomeOfActiveProfile(active: Long?, uiState: HomeUiState, content: @Composable (HomeUiState) -> Unit) {
    // Before the first read the placeholder's own profile stands in: a launcher with one profile keys its
    // content on the same value from its first frame on.
    val profile = active ?: uiState.homeProfileId
    if (uiState.loaded && uiState.homeProfileId != profile) return
    key(profile) { content(uiState) }
}
