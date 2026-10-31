package org.arkikeskus.launcher.model

/** User-configurable launcher settings (persisted in DataStore). */
data class LauncherSettings(
    val dockEnabled: Boolean = true,
    val dockColumns: Int = 4,
    val homeColumns: Int = 4,
    /** Home-grid row count — more rows = tighter icon rows + more free cells for widgets. */
    val homeRows: Int = 6,
    val drawerColumns: Int = 4,
    val showDrawerSearch: Boolean = true,
    val swipeUpForDrawer: Boolean = true,
    val swipeDownForNotifications: Boolean = true,
    /** Issue #17 (opt-in, Nova-style): a swipe up that starts on a HOME folder icon opens the folder's first app
     *  (in the folder's own order) instead of the drawer. Off, the swipe drives the drawer as from anywhere on
     *  home. Home only: dock folders and the drawer keep their gestures. */
    val folderSwipeOpensFirstApp: Boolean = false,
    val showDockLabels: Boolean = false,
    val showHomeLabels: Boolean = true,
    val showDrawerLabels: Boolean = true,
    /** The name under a home folder's tile (issue #17); off keeps the tile alone. Labels off hide it too. */
    val showHomeFolderNames: Boolean = true,
    /** The name under an app-drawer folder's tile; off keeps the tile alone. Labels off hide it too. */
    val showDrawerFolderNames: Boolean = true,
    val dockBackgroundOpacity: Float = DEFAULT_DOCK_BACKGROUND,
    /** Opacity of the app drawer's background (0 = the wallpaper shows through, 1 = solid); issue #21. */
    val drawerBackgroundOpacity: Float = DEFAULT_DRAWER_BACKGROUND,
    val showPageIndicator: Boolean = true,
    /** How the page dots mark the home page: a ring around its dot ([MARKER_RING]) or a house glyph
     *  in place of the dot ([MARKER_HOUSE]). */
    val homePageMarker: String = MARKER_RING,
    /** How fast the home pager settles on a page (its snap and every programmatic page change):
     *  [SPEED_NORMAL], [SPEED_FAST] or [SPEED_FASTEST] (issue #18). */
    val pageSwitchSpeed: String = SPEED_NORMAL,
    /** Swiping past the last home page continues to the first one, and back from the first to the
     *  last (unless the left-edge app takes that swipe). Off keeps the bounce back from the end. */
    val infiniteScroll: Boolean = true,
    /** "Not now" on the set-as-default card: it stays hidden on this device (Settings still offers
     *  the default-launcher row). Device-local, never in a backup. */
    val defaultReminderDismissed: Boolean = false,
    val showNotificationDots: Boolean = true,
    /** When notification dots are on: true shows the count (Nova-style), false shows a plain dot. */
    val notificationDotCount: Boolean = true,
    /** Size multiplier for the notification dot/badge (1.0 = default). */
    val notificationDotScale: Float = 1.0f,
    /** Render app icons as Material You themed (monochrome) icons where the app provides one. */
    val useThemedIcons: Boolean = false,
    /** Package name of the selected third-party icon pack (empty = system default). Overrides
     *  [useThemedIcons] when set: mapped apps use the pack's icon, unmapped apps are masked to its style. */
    val iconPackPackage: String = "",
    /** Include contacts in app-drawer search (gated by READ_CONTACTS; requested when enabled). */
    val searchContacts: Boolean = false,
    /** App key launched by the left-edge home swipe (Settings ▸ Eleet); blank = gesture disabled. */
    val leftSwipeAppKey: String = "",
    /** App key opened by a tap on the smartspace weather line (Settings > Home); blank = plain text. */
    val weatherAppKey: String = "",
    /** Lock the desktop layout: when true, home + dock items can't be moved, removed, or added. */
    val desktopLocked: Boolean = false,
    /** Show a "most used" row (top apps by decayed launch frequency) above the drawer's app list. */
    val showFrequentApps: Boolean = false,
    /** Reopen the app drawer scrolled back to the top (Pixel-style) rather than remembering the last
     *  scroll position — keeps the "most used" row and A–Z start visible on every open. */
    val drawerOpensAtTop: Boolean = true,
    /** List the app drawer's folders A–Z (case aside) instead of in the user's own order. */
    val sortDrawerFolders: Boolean = false,
    /** Apps placed on the home screen (its pages, its folders, the dock) leave the drawer's list; the search
     *  still finds them (a Telegram request after 0.9.7, opt-in). */
    val hideHomeAppsFromDrawer: Boolean = false,
    /** Size multiplier for app icon labels across home/dock/drawer/folders (1.0 = the default 11sp). */
    val appLabelTextScale: Float = 1.0f,
    /** Size multiplier for app icons across home/dock/drawer/folders (1.0 = each surface's nominal size;
     *  the cell still caps the result). */
    val iconScale: Float = 1.0f,
    /** Size multiplier for the app drawer's icons (its tiles and folder cards), apart from [iconScale] which
     *  sizes home, the dock and home folders. Unset it follows [iconScale], so the single slider of versions
     *  before 0.9.8 keeps looking the same (a Telegram request). */
    val drawerIconScale: Float = 1.0f,
    /** Multiplier on the system long-press timeout before an icon's menu opens or it can be moved
     *  (1.0 = the system's own delay, 0.5–2.0). */
    val longPressScale: Float = 1.0f,
    /** Let home-screen labels (apps, folders, shortcuts) wrap onto a second line. Off keeps the
     *  single line the home grid has always had; the dock stays single-line either way. */
    val twoLineHomeLabels: Boolean = false,
    /** Let app-drawer labels (apps, folders, the "most used" row) wrap onto a second line — the
     *  drawer's long-standing look, so this defaults on. */
    val twoLineDrawerLabels: Boolean = true,
    /** ARGB color for app icon labels on the home surfaces (home/dock/folders); default white. The
     *  app drawer keeps its theme color for readability over its solid background. */
    val appLabelColor: Int = 0xFFFFFFFF.toInt(),
    /** Show a slim status bar (clock + battery + signal, with dynamic battery/signal colors) at the top
     *  of the home screen. */
    val showStatusBar: Boolean = false,
    /** Show the current weather (Open-Meteo; needs a location permission) in the smartspace widget. */
    val showWeather: Boolean = true,
    /** Name the weather's place with BigDataCloud's reverse geocoder when the device's own Geocoder
     *  can't. Opt-in: BigDataCloud is a proprietary service (F-Droid's NonFreeNet anti-feature). */
    val networkPlaceNames: Boolean = false,
    /** Hide the system status bar while the launcher is in the foreground (immersive/fullscreen home).
     *  Independent of [showStatusBar]; combine the two to replace the system bar with the themed one. */
    val hideSystemStatusBar: Boolean = false,
    /** Darkness of the scrim drawn behind the themed status bar (0 = none, 1 = solid black); keeps the
     *  clock/indicators legible over bright wallpapers. Own setting, like [dockBackgroundOpacity]. */
    val statusBarScrimOpacity: Float = DEFAULT_STATUS_BAR_SCRIM,
    /** Count indicator of the built-in notifications widget: [COUNT_NUMBER], [COUNT_DOT] or [COUNT_NONE]. */
    /** Use theme surfaces for built-in widgets; false keeps the translucent wallpaper style. */
    val widgetTonalBackground: Boolean = false,
    /** Widgets keep the same side margin inside their cells as an app icon has inside its cell, instead of
     *  filling the span edge to edge (a Telegram request after 0.9.7, opt-in; Launcher3's widget padding). */
    val widgetEdgesAlignWithIcons: Boolean = false,
    val notificationWidgetCountStyle: String = COUNT_NUMBER,
    /** Double-tap on empty home-screen space locks the screen (needs the lock accessibility service). */
    val doubleTapToLock: Boolean = false,
    /** Play [lockSoundUri] when the phone locks and [unlockSoundUri] when it unlocks. */
    val lockSoundsEnabled: Boolean = false,
    /** The sound picked for locking (a content URI; "" = none). Kept on this phone only. */
    val lockSoundUri: String = "",
    /** The sound picked for unlocking (a content URI; "" = none). Kept on this phone only. */
    val unlockSoundUri: String = "",
    /** What the built-in people widget shows of a notification on the (shared) home screen:
     *  [PRIVACY_ALL] = sender + message text, [PRIVACY_SENDER] = sender only, [PRIVACY_COUNT] = the
     *  tile colors and counts but shows no text at all (pinned people keep their user-chosen name). */
    val peoplePrivacy: String = PRIVACY_ALL,
    /** Hold messages from people who aren't pinned and deliver them in batches at [peopleBatchTimes]
     *  in launcher surfaces only. Android notifications remain active so new messages from pinned
     *  people and one-time codes can bypass the batch. Missed calls always come through. */
    val peopleBatchEnabled: Boolean = false,
    /** Comma-separated "HH:mm" delivery times for the batch; see BatchSchedule. */
    val peopleBatchTimes: String = DEFAULT_BATCH_TIMES,
    /** Also show notifications that aren't from a person in the people widget, grouped by app. */
    val peopleShowApps: Boolean = true,
    /** The page the launcher opens on and the HOME button returns to (0 = the leftmost). */
    val homePage: Int = 0,
    /** Pages the user added explicitly (0 = none): the home keeps at least this many pages even
     *  when some are empty. Pages that hold an icon or widget exist regardless. */
    val homePageCount: Int = 0,
) {
    companion object {
        const val COUNT_NUMBER = "number"
        const val COUNT_DOT = "dot"
        const val COUNT_NONE = "none"

        const val MARKER_RING = "ring"
        const val MARKER_HOUSE = "house"

        const val SPEED_NORMAL = "normal"
        const val SPEED_FAST = "fast"
        const val SPEED_FASTEST = "fastest"

        const val PRIVACY_ALL = "all"
        const val PRIVACY_SENDER = "sender"
        const val PRIVACY_COUNT = "count"

        const val DEFAULT_BATCH_TIMES = "08:00,12:00,17:00"

        /** [statusBarScrimOpacity] out of the box: 60 % black keeps white status text readable even
         *  over a white wallpaper, without the band looking heavy. */
        const val DEFAULT_STATUS_BAR_SCRIM = 0.6f

        /** [dockBackgroundOpacity] out of the box: a light tint that sets the dock off the wallpaper. */
        const val DEFAULT_DOCK_BACKGROUND = 0.35f

        /** [drawerBackgroundOpacity] out of the box: solid, the drawer as it always was. */
        const val DEFAULT_DRAWER_BACKGROUND = 1f
    }
}
