package org.arkikeskus.launcher.ui

import androidx.annotation.StringRes

/**
 * A one-shot notice a screen shows (as a toast) when something the user asked for could not be
 * done to the home screen. The ViewModels emit these; the text lives here so the home screen and
 * the app drawer say the same thing.
 */
enum class HomeNotice(@StringRes val message: Int) {
    /** Every page the pager shows is full: nothing was added to or moved onto the home screen. */
    HomeFull(R.string.home_full),

    /** Only the default launcher may pause or unpause work apps (quiet mode): nothing changed. */
    WorkAppsNeedDefault(R.string.notice_work_apps_need_default),

    /** Only the default launcher may lock or unlock the private space: nothing changed. */
    PrivateSpaceNeedsDefault(R.string.notice_private_space_needs_default),

    /** The storage refused a change the user made (a full disk): what was there is still there. */
    ChangeNotSaved(R.string.notice_change_not_saved),

    /** The profile that was on could not be read, and another one came on in its place. The text says what
     *  happened, not which profile is shown: the notice waits for the home screen to be seen, and the user
     *  may have switched back by then. */
    ProfileNotRead(R.string.notice_profile_not_read),
}
