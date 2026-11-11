package org.arkikeskus.launcher.ui

import androidx.annotation.DrawableRes

/**
 * Shared vector-drawable icons (Material paths) used across the launcher's popups and rows, exposed
 * as resource ids so feature modules reference them without reaching into another module's R class.
 */
object LauncherIcons {
    @DrawableRes val Info: Int = R.drawable.ic_info
    @DrawableRes val Edit: Int = R.drawable.ic_edit
    @DrawableRes val Close: Int = R.drawable.ic_close
    @DrawableRes val Add: Int = R.drawable.ic_add
    @DrawableRes val Delete: Int = R.drawable.ic_delete
    @DrawableRes val VisibilityOff: Int = R.drawable.ic_visibility_off
    @DrawableRes val Call: Int = R.drawable.ic_call
    @DrawableRes val Message: Int = R.drawable.ic_message
    @DrawableRes val ChevronLeft: Int = R.drawable.ic_chevron_left
    @DrawableRes val ChevronRight: Int = R.drawable.ic_chevron_right
    @DrawableRes val Remove: Int = R.drawable.ic_remove
    @DrawableRes val OpenInNew: Int = R.drawable.ic_open_in_new
    @DrawableRes val Reply: Int = R.drawable.ic_reply
    @DrawableRes val DoneAll: Int = R.drawable.ic_done_all
    @DrawableRes val Check: Int = R.drawable.ic_check
    @DrawableRes val Person: Int = R.drawable.ic_person
    @DrawableRes val Pin: Int = R.drawable.ic_push_pin
    @DrawableRes val Unpin: Int = R.drawable.ic_push_pin_off
    @DrawableRes val Link: Int = R.drawable.ic_link
    @DrawableRes val LinkOff: Int = R.drawable.ic_link_off
    @DrawableRes val Home: Int = R.drawable.ic_home
    @DrawableRes val Widgets: Int = R.drawable.ic_widgets
    @DrawableRes val Work: Int = R.drawable.ic_work
    @DrawableRes val Play: Int = R.drawable.ic_play_arrow
    @DrawableRes val ExpandMore: Int = R.drawable.ic_expand_more
    @DrawableRes val ArrowBack: Int = R.drawable.ic_arrow_back
    @DrawableRes val Apps: Int = R.drawable.ic_apps
    @DrawableRes val Folder: Int = R.drawable.ic_folder
    @DrawableRes val Notifications: Int = R.drawable.ic_notifications
    @DrawableRes val VolumeUp: Int = R.drawable.ic_volume_up
    @DrawableRes val Dock: Int = R.drawable.ic_dock
    @DrawableRes val Category: Int = R.drawable.ic_category
    @DrawableRes val SwipeUp: Int = R.drawable.ic_swipe_up
    @DrawableRes val SystemBars: Int = R.drawable.ic_system_bars
    @DrawableRes val Shield: Int = R.drawable.ic_shield
    @DrawableRes val Backup: Int = R.drawable.ic_backup
}
