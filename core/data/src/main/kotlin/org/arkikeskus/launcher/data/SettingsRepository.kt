package org.arkikeskus.launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.arkikeskus.launcher.model.LauncherSettings
import javax.inject.Inject
import javax.inject.Singleton

/** A folder shown in the app drawer: a stable [id], a [name], and the keys of its member apps. */
data class DrawerFolder(val id: Long, val name: String, val appKeys: List<String>)

/**
 * Reads/writes launcher preferences. The [DataStore] is injected (provided from a Context-backed
 * `preferencesDataStore` in DataModule) so the repository's merge logic can be unit-tested on the
 * JVM with a temp-file store.
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    val settings: Flow<LauncherSettings> = dataStore.data.map { p ->
        // Clamp the numeric values on read as well as on write: a stale/garbage value left in the
        // store during development (e.g. homeColumns = 0) must never reach the layout math, where a
        // zero column count would spin firstFreeCell() in an infinite loop.
        LauncherSettings(
            dockEnabled = p[Keys.DOCK_ENABLED] ?: true,
            dockColumns = (p[Keys.DOCK_COLUMNS] ?: 4).coerceIn(MIN_COLUMNS, MAX_COLUMNS),
            homeColumns = (p[Keys.HOME_COLUMNS] ?: 4).coerceIn(MIN_COLUMNS, MAX_COLUMNS),
            homeRows = (p[Keys.HOME_ROWS] ?: 6).coerceIn(MIN_ROWS, MAX_ROWS),
            drawerColumns = (p[Keys.DRAWER_COLUMNS] ?: 4).coerceIn(MIN_COLUMNS, MAX_COLUMNS),
            showDrawerSearch = p[Keys.SHOW_DRAWER_SEARCH] ?: true,
            swipeUpForDrawer = p[Keys.SWIPE_UP_DRAWER] ?: true,
            swipeDownForNotifications = p[Keys.SWIPE_DOWN_NOTIF] ?: true,
            showDockLabels = p[Keys.SHOW_DOCK_LABELS] ?: false,
            showHomeLabels = p[Keys.SHOW_HOME_LABELS] ?: true,
            showDrawerLabels = p[Keys.SHOW_DRAWER_LABELS] ?: true,
            dockBackgroundOpacity = (p[Keys.DOCK_OPACITY] ?: 0.35f).coerceIn(0f, 1f),
            showPageIndicator = p[Keys.SHOW_PAGE_INDICATOR] ?: true,
            showNotificationDots = p[Keys.SHOW_NOTIF_DOTS] ?: true,
            notificationDotCount = p[Keys.NOTIF_DOT_COUNT] ?: true,
            notificationDotScale = (p[Keys.NOTIF_DOT_SCALE] ?: 1.0f).coerceIn(MIN_DOT_SCALE, MAX_DOT_SCALE),
            useThemedIcons = p[Keys.USE_THEMED_ICONS] ?: false,
            iconPackPackage = p[Keys.ICON_PACK] ?: "",
            searchContacts = p[Keys.SEARCH_CONTACTS] ?: false,
            leftSwipeAppKey = p[Keys.LEFT_SWIPE_APP_KEY] ?: "",
            desktopLocked = p[Keys.DESKTOP_LOCKED] ?: false,
            showFrequentApps = p[Keys.SHOW_FREQUENT_APPS] ?: false,
            drawerOpensAtTop = p[Keys.DRAWER_OPENS_AT_TOP] ?: true,
            appLabelTextScale = (p[Keys.APP_LABEL_SCALE] ?: 1.0f).coerceIn(MIN_LABEL_SCALE, MAX_LABEL_SCALE),
            appLabelColor = p[Keys.APP_LABEL_COLOR] ?: 0xFFFFFFFF.toInt(),
            showStatusBar = p[Keys.SHOW_STATUS_BAR] ?: false,
            showWeather = p[Keys.SHOW_WEATHER] ?: true,
            hideSystemStatusBar = p[Keys.HIDE_SYSTEM_STATUS_BAR] ?: false,
            statusBarScrimOpacity = (p[Keys.STATUS_BAR_SCRIM] ?: 0.6f).coerceIn(0f, 1f),
            notificationWidgetCountStyle = (p[Keys.NOTIF_WIDGET_COUNT_STYLE] ?: LauncherSettings.COUNT_NUMBER)
                .let { if (it == LauncherSettings.COUNT_DOT || it == LauncherSettings.COUNT_NONE) it else LauncherSettings.COUNT_NUMBER },
            doubleTapToLock = p[Keys.DOUBLE_TAP_LOCK] ?: false,
        )
    }

    suspend fun setDoubleTapToLock(value: Boolean) = edit { it[Keys.DOUBLE_TAP_LOCK] = value }

    /** Ordered list of dock favorite app keys (see AppItem.key). */
    val dockFavorites: Flow<List<String>> = dataStore.data.map { p ->
        p[Keys.DOCK_FAVORITES]?.split("\n")?.filter { it.isNotEmpty() } ?: emptyList()
    }

    /** App keys hidden from the app drawer (see AppItem.key). */
    val hiddenApps: Flow<Set<String>> = dataStore.data.map { p ->
        p[Keys.HIDDEN_APPS]?.split("\n")?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    }

    suspend fun setAppHidden(key: String, hidden: Boolean) = edit { p ->
        val current = (p[Keys.HIDDEN_APPS]?.split("\n")?.filter { it.isNotEmpty() } ?: emptyList()).toMutableSet()
        if (hidden) current.add(key) else current.remove(key)
        p[Keys.HIDDEN_APPS] = current.joinToString("\n")
    }

    /** User-chosen custom app labels (AppItem.key -> label), applied over the system label. */
    val customLabels: Flow<Map<String, String>> = dataStore.data.map { p -> parseLabels(p[Keys.CUSTOM_LABELS]) }

    /** Sets (or, for a blank [label], clears) the custom label for [key]. */
    suspend fun setCustomLabel(key: String, label: String?) = edit { p ->
        val current = parseLabels(p[Keys.CUSTOM_LABELS]).toMutableMap()
        val trimmed = label?.trim().orEmpty()
        if (trimmed.isEmpty()) current.remove(key) else current[key] = trimmed
        p[Keys.CUSTOM_LABELS] = current.entries.joinToString("\n") { "${it.key}\t${it.value}" }
    }

    private fun parseLabels(raw: String?): Map<String, String> =
        raw?.split("\n")?.filter { it.isNotEmpty() }?.mapNotNull { line ->
            val i = line.indexOf('\t')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
        }?.toMap() ?: emptyMap()

    // --- App-drawer folders ----------------------------------------------------------------------
    // Serialized one folder per line, tab-separated fields: "id\tname\tkey1\tkey2...". App keys
    // never contain a tab; folder names are normalized in serializeFolders, so a plain split
    // round-trips cleanly.

    val drawerFolders: Flow<List<DrawerFolder>> = dataStore.data.map { p -> parseFolders(p[Keys.DRAWER_FOLDERS]) }

    /** Creates an empty drawer folder, returns its new id. */
    suspend fun createDrawerFolder(name: String): Long {
        var newId = 1L
        edit { p ->
            val folders = parseFolders(p[Keys.DRAWER_FOLDERS])
            newId = (folders.maxOfOrNull { it.id } ?: 0L) + 1L
            p[Keys.DRAWER_FOLDERS] = serializeFolders(folders + DrawerFolder(newId, name, emptyList()))
        }
        return newId
    }

    suspend fun renameDrawerFolder(id: Long, name: String) = editFolders { folders ->
        folders.map { if (it.id == id) it.copy(name = name) else it }
    }

    suspend fun deleteDrawerFolder(id: Long) = editFolders { folders -> folders.filterNot { it.id == id } }

    suspend fun addAppsToDrawerFolder(id: Long, keys: List<String>) = editFolders { folders ->
        folders.map { f ->
            if (f.id == id) f.copy(appKeys = (f.appKeys + keys).distinct()) else f
        }
    }

    suspend fun removeAppFromDrawerFolder(id: Long, key: String) = editFolders { folders ->
        folders.map { f -> if (f.id == id) f.copy(appKeys = f.appKeys - key) else f }
    }

    private suspend fun editFolders(block: (List<DrawerFolder>) -> List<DrawerFolder>) = edit { p ->
        p[Keys.DRAWER_FOLDERS] = serializeFolders(block(parseFolders(p[Keys.DRAWER_FOLDERS])))
    }

    private fun parseFolders(raw: String?): List<DrawerFolder> =
        raw?.split("\n")?.filter { it.isNotEmpty() }?.mapNotNull { line ->
            val parts = line.split('\t')
            val id = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            DrawerFolder(id, parts.getOrNull(1).orEmpty(), parts.drop(2).filter { it.isNotEmpty() })
        } ?: emptyList()

    private fun serializeFolders(folders: List<DrawerFolder>): String =
        folders.joinToString("\n") { f ->
            // The record format is tab/newline-separated; a pasted separator in a folder name would
            // truncate the record and corrupt the member list — normalize at the one write point.
            val safeName = f.name.replace(SEPARATORS, " ")
            (listOf(f.id.toString(), safeName) + f.appKeys).joinToString("\t")
        }

    private val SEPARATORS = Regex("[\\t\\n\\r]+")

    suspend fun setDockEnabled(value: Boolean) = edit { it[Keys.DOCK_ENABLED] = value }
    suspend fun setDockColumns(value: Int) = edit { it[Keys.DOCK_COLUMNS] = value.coerceIn(MIN_COLUMNS, MAX_COLUMNS) }
    suspend fun setHomeColumns(value: Int) = edit { it[Keys.HOME_COLUMNS] = value.coerceIn(MIN_COLUMNS, MAX_COLUMNS) }
    suspend fun setHomeRows(value: Int) = edit { it[Keys.HOME_ROWS] = value.coerceIn(MIN_ROWS, MAX_ROWS) }
    suspend fun setDrawerColumns(value: Int) = edit { it[Keys.DRAWER_COLUMNS] = value.coerceIn(MIN_COLUMNS, MAX_COLUMNS) }
    suspend fun setShowDrawerSearch(value: Boolean) = edit { it[Keys.SHOW_DRAWER_SEARCH] = value }
    suspend fun setSwipeUpForDrawer(value: Boolean) = edit { it[Keys.SWIPE_UP_DRAWER] = value }
    suspend fun setSwipeDownForNotifications(value: Boolean) = edit { it[Keys.SWIPE_DOWN_NOTIF] = value }
    suspend fun setShowDockLabels(value: Boolean) = edit { it[Keys.SHOW_DOCK_LABELS] = value }
    suspend fun setShowHomeLabels(value: Boolean) = edit { it[Keys.SHOW_HOME_LABELS] = value }
    suspend fun setShowDrawerLabels(value: Boolean) = edit { it[Keys.SHOW_DRAWER_LABELS] = value }
    suspend fun setDockBackgroundOpacity(value: Float) = edit { it[Keys.DOCK_OPACITY] = value.coerceIn(0f, 1f) }
    suspend fun setShowPageIndicator(value: Boolean) = edit { it[Keys.SHOW_PAGE_INDICATOR] = value }
    suspend fun setShowNotificationDots(value: Boolean) = edit { it[Keys.SHOW_NOTIF_DOTS] = value }
    suspend fun setNotificationDotCount(value: Boolean) = edit { it[Keys.NOTIF_DOT_COUNT] = value }
    suspend fun setNotificationDotScale(value: Float) =
        edit { it[Keys.NOTIF_DOT_SCALE] = value.coerceIn(MIN_DOT_SCALE, MAX_DOT_SCALE) }
    suspend fun setUseThemedIcons(value: Boolean) = edit { it[Keys.USE_THEMED_ICONS] = value }

    /** Sets (or clears, for blank) the selected third-party icon pack package. */
    suspend fun setIconPackPackage(pkg: String) = edit { it[Keys.ICON_PACK] = pkg.trim() }

    /** Count indicator style of the built-in notifications widget (see LauncherSettings.COUNT_*). */
    suspend fun setNotificationWidgetCountStyle(style: String) = edit { it[Keys.NOTIF_WIDGET_COUNT_STYLE] = style }
    suspend fun setSearchContacts(value: Boolean) = edit { it[Keys.SEARCH_CONTACTS] = value }

    /** Sets (or, for a blank/null [key], clears) the app launched by the left-edge home swipe. */
    suspend fun setLeftSwipeAppKey(key: String?) = edit { it[Keys.LEFT_SWIPE_APP_KEY] = key?.trim().orEmpty() }

    /** Locks/unlocks the desktop layout (blocks moving/removing/adding home + dock items). */
    suspend fun setDesktopLocked(value: Boolean) = edit { it[Keys.DESKTOP_LOCKED] = value }

    /** Toggles the "most used" row in the app drawer. */
    suspend fun setShowFrequentApps(value: Boolean) = edit { it[Keys.SHOW_FREQUENT_APPS] = value }

    /** Whether the app drawer reopens at the top vs. remembers its last scroll position. */
    suspend fun setDrawerOpensAtTop(value: Boolean) = edit { it[Keys.DRAWER_OPENS_AT_TOP] = value }

    /** Size multiplier for app icon labels (clamped to the slider's range). */
    suspend fun setAppLabelTextScale(value: Float) =
        edit { it[Keys.APP_LABEL_SCALE] = value.coerceIn(MIN_LABEL_SCALE, MAX_LABEL_SCALE) }

    /** ARGB color for the home-surface app icon labels. */
    suspend fun setAppLabelColor(argb: Int) = edit { it[Keys.APP_LABEL_COLOR] = argb }

    /** Shows/hides the home status bar (clock + battery + signal). */
    suspend fun setShowStatusBar(value: Boolean) = edit { it[Keys.SHOW_STATUS_BAR] = value }

    suspend fun setShowWeather(value: Boolean) = edit { it[Keys.SHOW_WEATHER] = value }

    /** Hides/shows the system status bar while the launcher is foreground (immersive home). */
    suspend fun setHideSystemStatusBar(value: Boolean) = edit { it[Keys.HIDE_SYSTEM_STATUS_BAR] = value }

    /** Darkness of the themed status bar's scrim (0..1). */
    suspend fun setStatusBarScrimOpacity(value: Float) =
        edit { it[Keys.STATUS_BAR_SCRIM] = value.coerceIn(0f, 1f) }

    suspend fun addToDock(key: String) = edit { p ->
        val current = currentFavorites(p).toMutableList()
        if (key !in current) current.add(key)
        p[Keys.DOCK_FAVORITES] = current.joinToString("\n")
    }

    suspend fun removeFromDock(key: String) = edit { p ->
        val current = currentFavorites(p).toMutableList()
        current.remove(key)
        p[Keys.DOCK_FAVORITES] = current.joinToString("\n")
    }

    /**
     * Inserts [key] into the dock favorites at [index] (clamped), used when an icon is dragged into
     * the dock. Any existing occurrence is removed first, so this also re-positions a key already in
     * the dock and never creates duplicates.
     */
    suspend fun addToDockAt(key: String, index: Int) = edit { p ->
        val current = currentFavorites(p).toMutableList()
        current.remove(key)
        current.add(index.coerceIn(0, current.size), key)
        p[Keys.DOCK_FAVORITES] = current.joinToString("\n")
    }

    /**
     * Reorders only the currently visible dock favorites while keeping any favorites hidden by the
     * `dockColumns` cap. The dock UI only ever sees the first `dockColumns` keys, so it must not be
     * allowed to overwrite the full list — the hidden tail is preserved after the new visible order.
     */
    suspend fun reorderVisibleDock(visibleKeys: List<String>) = edit { p ->
        val normalizedVisible = visibleKeys.distinct()
        val hiddenTail = currentFavorites(p).filter { it !in normalizedVisible }
        p[Keys.DOCK_FAVORITES] = (normalizedVisible + hiddenTail).joinToString("\n")
    }

    private fun currentFavorites(p: MutablePreferences): List<String> =
        p[Keys.DOCK_FAVORITES]?.split("\n")?.filter { it.isNotEmpty() } ?: emptyList()

    /** Timestamp (epoch ms) of the last successful local file export; 0 if never. */
    val localLastBackupTime: Flow<Long> = dataStore.data.map { it[Keys.LOCAL_LAST_BACKUP] ?: 0L }

    suspend fun setLocalLastBackup(timeMs: Long) = edit { it[Keys.LOCAL_LAST_BACKUP] = timeMs }

    // --- First-run default layout + onboarding (device-local) ---
    suspend fun defaultLayoutSeededOnce(): Boolean = dataStore.data.first()[Keys.DEFAULT_LAYOUT_SEEDED] ?: false
    suspend fun setDefaultLayoutSeeded() = edit { it[Keys.DEFAULT_LAYOUT_SEEDED] = true }
    suspend fun onboardingDoneOnce(): Boolean = dataStore.data.first()[Keys.ONBOARDING_DONE] ?: false
    suspend fun setOnboardingDone() = edit { it[Keys.ONBOARDING_DONE] = true }

    /** One-time first-run freshness decision, persisted BEFORE any seeding writes so a mid-seed
     *  death can't flip a fresh install into an "updating user". Null = not decided yet. */
    suspend fun firstRunFreshOnce(): Boolean? =
        when (dataStore.data.first()[Keys.FIRST_RUN_FRESH]) {
            "yes" -> true
            "no" -> false
            else -> null
        }
    suspend fun setFirstRunFresh(fresh: Boolean) = edit { it[Keys.FIRST_RUN_FRESH] = if (fresh) "yes" else "no" }

    /** Replaces the (empty) dock with the first-run default apps — seeding only, not a user API. */
    suspend fun seedDock(keys: List<String>) = edit { it[Keys.DOCK_FAVORITES] = keys.joinToString("\n") }

    /**
     * Snapshot of every persisted preference (name -> value) for backup.
     * Device-local bookkeeping keys are excluded so a restore never reimports another device's
     * state (this also keeps the ≤0.7.11 Drive/updater leftovers on an upgraded device out of new
     * exports); the volatile per-launch [AppUsageRepository.USAGE_KEY] is excluded because it is
     * device-local behavioural data, not worth carrying to a new phone.
     */
    suspend fun exportRaw(): Map<String, Any> =
        dataStore.data.first().asMap().entries
            .filterNot { it.key.name in DEVICE_LOCAL_KEYS || it.key.name == AppUsageRepository.USAGE_KEY }
            .associate { (k, v) -> k.name to v }

    /**
     * Replaces all preferences with [values]. Known keys are written only with their registered
     * type (see [BOOLEAN_KEYS]/[STRING_KEYS]/[FLOAT_KEYS]/[INT_KEYS]); JSON collapses Int/Float
     * into "number", so numeric values are coerced back by the registry; unknown keys fall back
     * to their JSON type.
     *
     * The device-local bookkeeping keys in [DEVICE_LOCAL_KEYS] (the local file-backup time and the
     * first-run/onboarding flags) are snapshotted before the clear and re-applied afterward, so
     * restoring a backup never wipes this device's local state.
     */
    suspend fun importRaw(values: Map<String, Any>) {
        dataStore.edit { prefs ->
            // Snapshot device-local bookkeeping before clearing.
            val localLastBackup = prefs[Keys.LOCAL_LAST_BACKUP]
            val layoutSeeded = prefs[Keys.DEFAULT_LAYOUT_SEEDED]
            val onboardingDone = prefs[Keys.ONBOARDING_DONE]
            val firstRunFresh = prefs[Keys.FIRST_RUN_FRESH]
            // Device-local usage stats aren't in the backup (see exportRaw) — preserve this device's.
            val appUsage = prefs[stringPreferencesKey(AppUsageRepository.USAGE_KEY)]
            prefs.clear()
            for ((name, value) in values) {
                when {
                    // Device-local bookkeeping is excluded from export, but a hand-edited (or old,
                    // ≤0.7.11 Drive-era) file could smuggle a value in — never import these names.
                    name in DEVICE_LOCAL_KEYS || name == AppUsageRepository.USAGE_KEY -> Unit
                    // Known keys are written ONLY with their registered type — a wrong-typed value
                    // in an edited/corrupted file would otherwise be stored under the same key name
                    // and crash every settings read with a ClassCastException.
                    name in BOOLEAN_KEYS -> if (value is Boolean) prefs[booleanPreferencesKey(name)] = value
                    name in STRING_KEYS -> if (value is String) prefs[stringPreferencesKey(name)] = value
                    name in FLOAT_KEYS -> if (value is Number) prefs[floatPreferencesKey(name)] = value.toFloat()
                    name in INT_KEYS -> if (value is Number) prefs[intPreferencesKey(name)] = value.toInt()
                    // Unknown key (e.g. a newer version's setting) — fall back to the JSON type.
                    value is Boolean -> prefs[booleanPreferencesKey(name)] = value
                    value is String -> prefs[stringPreferencesKey(name)] = value
                    value is Number -> prefs[longPreferencesKey(name)] = value.toLong()
                }
            }
            // Re-apply device-local bookkeeping so this device's state is preserved.
            if (localLastBackup != null) prefs[Keys.LOCAL_LAST_BACKUP] = localLastBackup
            if (layoutSeeded != null) prefs[Keys.DEFAULT_LAYOUT_SEEDED] = layoutSeeded
            if (onboardingDone != null) prefs[Keys.ONBOARDING_DONE] = onboardingDone
            if (firstRunFresh != null) prefs[Keys.FIRST_RUN_FRESH] = firstRunFresh
            if (appUsage != null) prefs[stringPreferencesKey(AppUsageRepository.USAGE_KEY)] = appUsage
        }
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    private object Keys {
        val DOCK_ENABLED = booleanPreferencesKey("dock_enabled")
        val DOCK_COLUMNS = intPreferencesKey("dock_columns")
        val HOME_COLUMNS = intPreferencesKey("home_columns")
        val HOME_ROWS = intPreferencesKey("home_rows")
        val DRAWER_COLUMNS = intPreferencesKey("drawer_columns")
        val SHOW_DRAWER_SEARCH = booleanPreferencesKey("show_drawer_search")
        val SWIPE_UP_DRAWER = booleanPreferencesKey("swipe_up_drawer")
        val SWIPE_DOWN_NOTIF = booleanPreferencesKey("swipe_down_notif")
        val DOCK_FAVORITES = stringPreferencesKey("dock_favorites")
        val HIDDEN_APPS = stringPreferencesKey("hidden_apps")
        val CUSTOM_LABELS = stringPreferencesKey("custom_labels")
        val DRAWER_FOLDERS = stringPreferencesKey("drawer_folders")
        val SHOW_DOCK_LABELS = booleanPreferencesKey("show_dock_labels")
        val SHOW_HOME_LABELS = booleanPreferencesKey("show_home_labels")
        val SHOW_DRAWER_LABELS = booleanPreferencesKey("show_drawer_labels")
        val DOCK_OPACITY = floatPreferencesKey("dock_opacity")
        val SHOW_PAGE_INDICATOR = booleanPreferencesKey("show_page_indicator")
        val SHOW_NOTIF_DOTS = booleanPreferencesKey("show_notif_dots")
        val NOTIF_DOT_COUNT = booleanPreferencesKey("notif_dot_count")
        val NOTIF_DOT_SCALE = floatPreferencesKey("notif_dot_scale")
        val NOTIF_WIDGET_COUNT_STYLE = stringPreferencesKey("notif_widget_count_style")
        val USE_THEMED_ICONS = booleanPreferencesKey("use_themed_icons")
        val ICON_PACK = stringPreferencesKey("icon_pack_package")
        val SEARCH_CONTACTS = booleanPreferencesKey("search_contacts")
        val LEFT_SWIPE_APP_KEY = stringPreferencesKey("left_swipe_app_key")
        val DESKTOP_LOCKED = booleanPreferencesKey("desktop_locked")
        val DOUBLE_TAP_LOCK = booleanPreferencesKey("double_tap_lock")
        val SHOW_FREQUENT_APPS = booleanPreferencesKey("show_frequent_apps")
        val DRAWER_OPENS_AT_TOP = booleanPreferencesKey("drawer_opens_at_top")
        val LOCAL_LAST_BACKUP = longPreferencesKey("local_last_backup_time")
        val APP_LABEL_SCALE = floatPreferencesKey("app_label_scale")
        val APP_LABEL_COLOR = intPreferencesKey("app_label_color")
        val SHOW_STATUS_BAR = booleanPreferencesKey("show_status_bar")
        val SHOW_WEATHER = booleanPreferencesKey("show_weather")
        val HIDE_SYSTEM_STATUS_BAR = booleanPreferencesKey("hide_system_status_bar")
        val STATUS_BAR_SCRIM = floatPreferencesKey("status_bar_scrim")
        val DEFAULT_LAYOUT_SEEDED = booleanPreferencesKey("default_layout_seeded")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val FIRST_RUN_FRESH = stringPreferencesKey("first_run_fresh")
    }

    companion object {
        /** Valid range for the home/dock/drawer column counts (mirrors the settings steppers). */
        const val MIN_COLUMNS = 3
        const val MAX_COLUMNS = 7

        /** Valid range for the home-grid row count (mirrors the settings stepper). */
        const val MIN_ROWS = 5
        const val MAX_ROWS = 8

        /** Valid range for the notification-dot scale slider. */
        const val MIN_DOT_SCALE = 0.6f
        const val MAX_DOT_SCALE = 1.8f

        /** Valid range for the app-label text-size slider. */
        const val MIN_LABEL_SCALE = 0.8f
        const val MAX_LABEL_SCALE = 1.6f

        /** Preference keys whose value must be restored as Float (JSON loses the Int/Float distinction). */
        val FLOAT_KEYS = setOf("dock_opacity", "notif_dot_scale", "app_label_scale", "status_bar_scrim")
        val INT_KEYS = setOf("dock_columns", "home_columns", "home_rows", "drawer_columns", "app_label_color")

        /** Known boolean/string preference keys. importRaw writes a known key ONLY with its
         *  registered type — a wrong-typed value in an edited/corrupted backup would otherwise be
         *  stored under the same key name and crash every settings read with a ClassCastException. */
        val BOOLEAN_KEYS = setOf(
            "dock_enabled", "show_drawer_search", "swipe_up_drawer", "swipe_down_notif",
            "show_dock_labels", "show_home_labels", "show_drawer_labels", "show_page_indicator",
            "show_notif_dots", "notif_dot_count", "use_themed_icons", "search_contacts",
            "desktop_locked", "show_frequent_apps", "drawer_opens_at_top", "show_status_bar",
            "show_weather", "hide_system_status_bar", "double_tap_lock",
        )
        val STRING_KEYS = setOf(
            "dock_favorites", "hidden_apps", "custom_labels", "drawer_folders",
            "notif_widget_count_style", "icon_pack_package", "left_swipe_app_key",
        )

        /** Device-local bookkeeping keys excluded from an exported backup and never imported.
         *  The `drive_*`/`update_*` names are ≤0.7.11 leftovers (the Drive backup and in-app
         *  updater were removed in 0.7.12): upgraded devices still carry them in the DataStore and
         *  old backup files still contain them, so the names must stay blocked here. */
        val DEVICE_LOCAL_KEYS = setOf(
            "drive_backup_enabled", "drive_last_backup_time", "drive_last_backup_hash",
            "drive_failure_count", "local_last_backup_time",
            "drive_interval_days", "drive_wifi_only", "drive_charging_only",
            "auto_update_enabled", "update_last_check", "update_last_notified_version",
            "default_layout_seeded", "onboarding_done", "first_run_fresh",
        )
    }
}
