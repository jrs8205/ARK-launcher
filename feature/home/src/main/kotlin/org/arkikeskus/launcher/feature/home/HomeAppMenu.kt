package org.arkikeskus.launcher.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.ui.AppActionPopup
import org.arkikeskus.launcher.ui.AppActions
import org.arkikeskus.launcher.ui.AppShortcuts
import org.arkikeskus.launcher.ui.LauncherIcons
import org.arkikeskus.launcher.ui.PopupAction

/**
 * The long-press menu of an app on the home screen or in the dock ([inDock]): the shared
 * [AppActionPopup] with this surface's rows. On a [locked] desktop the menu still opens (issue #30, a
 * follow-up), with the rows that change nothing there: the app's page in Android's settings and its uninstall.
 */
@Composable
internal fun HomeAppMenu(
    app: AppItem,
    anchor: IntOffset,
    preferAbove: Boolean,
    inDock: Boolean,
    locked: Boolean,
    /** The finger that lifted the icon is still down (the menu opens at the lift, issue #12). */
    held: Boolean,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    onPinShortcut: (AppShortcuts.Item) -> Unit,
    /** The Widgets row (an app with home-screen widgets); null hides it. */
    onWidgets: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AppActionPopup(
        app = app,
        anchor = anchor,
        preferAbove = preferAbove,
        actions = listOf(
            PopupAction(stringResource(R.string.app_info), LauncherIcons.Info, keptWhenLocked = true) {
                AppActions.openAppInfo(context, app)
            },
            PopupAction(stringResource(R.string.rename), LauncherIcons.Edit, onClick = onRename),
            PopupAction(
                stringResource(if (inDock) R.string.dock_remove else R.string.home_remove),
                LauncherIcons.Close,
                onClick = onRemove,
            ),
            PopupAction(stringResource(R.string.uninstall), LauncherIcons.Delete, keptWhenLocked = true) {
                AppActions.uninstall(context, app)
            },
        ),
        onDismiss = onDismiss,
        onPinShortcut = onPinShortcut,
        onWidgets = onWidgets,
        held = held,
        locked = locked,
    )
}
