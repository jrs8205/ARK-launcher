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
            appLabelTextScale = (p[Keys.APP_LABEL_SCALE] ?: 1.0f).coerceIn(MIN_LABEL_SCALE, MAX_LABEL_SCALE),
            appLabelColor = p[Keys.APP_LABEL_COLOR] ?: 0xFFFFFFFF.toInt(),
            showStatusBar = p[Keys.SHOW_STATUS_BAR] ?: false,
            hideSystemStatusBar = p[Keys.HIDE_SYSTEM_STATUS_BAR] ?: false,
            statusBarScrimOpacity = (p[Keys.STATUS_BAR_SCRIM] ?: 0.6f).coerceIn(0f, 1f),
        )
    }

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
    // Serialized one folder per line, tab-separated fields: "id\tname\tkey1\tkey2...". App keys and
    // folder names never contain a tab, so a plain split round-trips cleanly.

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
        folders.joinToString("\n") { f -> (listOf(f.id.toString(), f.name) + f.appKeys).joinToString("\t") }

    suspend fun setDockEnabled(value: Boolean) = edit { it[Keys.DOCK_ENABLED] = value }
    suspend fun setDockColumns(value: Int) = edit { it[Keys.DOCK_COLUMNS] = value.coerceIn(MIN_COLUMNS, MAX_COLUMNS) }
    suspend fun setHomeColumns(value: Int) = edit { it[Keys.HOME_COLUMNS] = value.coerceIn(MIN_COLUMNS, MAX_COLUMNS) }
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
    suspend fun setSearchContacts(value: Boolean) = edit { it[Keys.SEARCH_CONTACTS] = value }

    /** Sets (or, for a blank/null [key], clears) the app launched by the left-edge home swipe. */
    suspend fun setLeftSwipeAppKey(key: String?) = edit { it[Keys.LEFT_SWIPE_APP_KEY] = key?.trim().orEmpty() }

    /** Locks/unlocks the desktop layout (blocks moving/removing/adding home + dock items). */
    suspend fun setDesktopLocked(value: Boolean) = edit { it[Keys.DESKTOP_LOCKED] = value }

    /** Toggles the "most used" row in the app drawer. */
    suspend fun setShowFrequentApps(value: Boolean) = edit { it[Keys.SHOW_FREQUENT_APPS] = value }

    /** Size multiplier for app icon labels (clamped to the slider's range). */
    suspend fun setAppLabelTextScale(value: Float) =
        edit { it[Keys.APP_LABEL_SCALE] = value.coerceIn(MIN_LABEL_SCALE, MAX_LABEL_SCALE) }

    /** ARGB color for the home-surface app icon labels. */
    suspend fun setAppLabelColor(argb: Int) = edit { it[Keys.APP_LABEL_COLOR] = argb }

    /** Shows/hides the home status bar (clock + battery + signal). */
    suspend fun setShowStatusBar(value: Boolean) = edit { it[Keys.SHOW_STATUS_BAR] = value }

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

    // --- Drive backup bookkeeping ---

    val driveEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.DRIVE_ENABLED] ?: false }
    val driveLastBackupTime: Flow<Long> = dataStore.data.map { it[Keys.DRIVE_LAST_TIME] ?: 0L }

    suspend fun setDriveEnabled(v: Boolean) = edit { it[Keys.DRIVE_ENABLED] = v }

    suspend fun setDriveLastBackup(timeMs: Long, hash: String) = edit {
        it[Keys.DRIVE_LAST_TIME] = timeMs; it[Keys.DRIVE_LAST_HASH] = hash
    }

    suspend fun driveLastHash(): String? = dataStore.data.first()[Keys.DRIVE_LAST_HASH]

    /** One-shot read for use in background workers (Task 7). */
    suspend fun driveEnabledOnce(): Boolean = dataStore.data.first()[Keys.DRIVE_ENABLED] ?: false

    /** Timestamp (epoch ms) of the last successful local file export; 0 if never. */
    val localLastBackupTime: Flow<Long> = dataStore.data.map { it[Keys.LOCAL_LAST_BACKUP] ?: 0L }

    suspend fun setLocalLastBackup(timeMs: Long) = edit { it[Keys.LOCAL_LAST_BACKUP] = timeMs }

    // --- Drive backup scheduling options (device-local) ---
    /** Periodic backup interval in days (1 = daily, 7 = weekly). */
    val driveIntervalDays: Flow<Int> = dataStore.data.map { it[Keys.DRIVE_INTERVAL_DAYS] ?: 1 }
    val driveWifiOnly: Flow<Boolean> = dataStore.data.map { it[Keys.DRIVE_WIFI_ONLY] ?: false }
    val driveChargingOnly: Flow<Boolean> = dataStore.data.map { it[Keys.DRIVE_CHARGING_ONLY] ?: false }

    suspend fun setDriveIntervalDays(days: Int) = edit { it[Keys.DRIVE_INTERVAL_DAYS] = days }
    suspend fun setDriveWifiOnly(v: Boolean) = edit { it[Keys.DRIVE_WIFI_ONLY] = v }
    suspend fun setDriveChargingOnly(v: Boolean) = edit { it[Keys.DRIVE_CHARGING_ONLY] = v }

    // --- In-app updater bookkeeping (device-local) ---
    val autoUpdateEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.AUTO_UPDATE_ENABLED] ?: true }
    val updateLastCheck: Flow<Long> = dataStore.data.map { it[Keys.UPDATE_LAST_CHECK] ?: 0L }

    suspend fun setAutoUpdateEnabled(v: Boolean) = edit { it[Keys.AUTO_UPDATE_ENABLED] = v }
    suspend fun setUpdateLastCheck(timeMs: Long) = edit { it[Keys.UPDATE_LAST_CHECK] = timeMs }
    suspend fun setUpdateLastNotifiedVersion(v: String) = edit { it[Keys.UPDATE_LAST_NOTIFIED] = v }
    suspend fun updateLastNotifiedVersion(): String? = dataStore.data.first()[Keys.UPDATE_LAST_NOTIFIED]
    suspend fun autoUpdateEnabledOnce(): Boolean = dataStore.data.first()[Keys.AUTO_UPDATE_ENABLED] ?: true

    /**
     * Snapshot of every persisted preference (name -> value) for backup.
     * Drive bookkeeping keys are excluded so a restore never reimports another device's Drive state.
     */
    suspend fun exportRaw(): Map<String, Any> =
        dataStore.data.first().asMap().entries
            .filterNot { it.key.name in DRIVE_INTERNAL_KEYS }
            .associate { (k, v) -> k.name to v }

    /**
     * Replaces all preferences with [values]. JSON collapses Int/Float into "number", so numeric
     * values are coerced back by the known-key registry; unknown keys fall back to their JSON type.
     *
     * The device-local bookkeeping keys in [DRIVE_INTERNAL_KEYS] (Drive enable / last-time / last-hash,
     * the local file-backup time, and the Drive scheduling options interval/Wi-Fi/charging) are
     * snapshotted before the clear and re-applied afterward, so restoring a backup never wipes this
     * device's Drive enable state, last-backup timestamps, or scheduling preferences.
     */
    suspend fun importRaw(values: Map<String, Any>) {
        dataStore.edit { prefs ->
            // Snapshot Drive bookkeeping before clearing.
            val driveEnabled = prefs[Keys.DRIVE_ENABLED]
            val driveLastTime = prefs[Keys.DRIVE_LAST_TIME]
            val driveLastHash = prefs[Keys.DRIVE_LAST_HASH]
            val localLastBackup = prefs[Keys.LOCAL_LAST_BACKUP]
            val driveInterval = prefs[Keys.DRIVE_INTERVAL_DAYS]
            val driveWifiOnly = prefs[Keys.DRIVE_WIFI_ONLY]
            val driveChargingOnly = prefs[Keys.DRIVE_CHARGING_ONLY]
            val autoUpdate = prefs[Keys.AUTO_UPDATE_ENABLED]
            val updateLastCheck = prefs[Keys.UPDATE_LAST_CHECK]
            val updateLastNotified = prefs[Keys.UPDATE_LAST_NOTIFIED]
            prefs.clear()
            for ((name, value) in values) {
                when {
                    value is Boolean -> prefs[booleanPreferencesKey(name)] = value
                    value is String -> prefs[stringPreferencesKey(name)] = value
                    name in FLOAT_KEYS && value is Number -> prefs[floatPreferencesKey(name)] = value.toFloat()
                    name in INT_KEYS && value is Number -> prefs[intPreferencesKey(name)] = value.toInt()
                    value is Number -> prefs[longPreferencesKey(name)] = value.toLong()
                }
            }
            // Re-apply Drive bookkeeping so this device's Drive state is preserved.
            if (driveEnabled != null) prefs[Keys.DRIVE_ENABLED] = driveEnabled
            if (driveLastTime != null) prefs[Keys.DRIVE_LAST_TIME] = driveLastTime
            if (driveLastHash != null) prefs[Keys.DRIVE_LAST_HASH] = driveLastHash
            if (localLastBackup != null) prefs[Keys.LOCAL_LAST_BACKUP] = localLastBackup
            if (driveInterval != null) prefs[Keys.DRIVE_INTERVAL_DAYS] = driveInterval
            if (driveWifiOnly != null) prefs[Keys.DRIVE_WIFI_ONLY] = driveWifiOnly
            if (driveChargingOnly != null) prefs[Keys.DRIVE_CHARGING_ONLY] = driveChargingOnly
            if (autoUpdate != null) prefs[Keys.AUTO_UPDATE_ENABLED] = autoUpdate
            if (updateLastCheck != null) prefs[Keys.UPDATE_LAST_CHECK] = updateLastCheck
            if (updateLastNotified != null) prefs[Keys.UPDATE_LAST_NOTIFIED] = updateLastNotified
        }
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    private object Keys {
        val DOCK_ENABLED = booleanPreferencesKey("dock_enabled")
        val DOCK_COLUMNS = intPreferencesKey("dock_columns")
        val HOME_COLUMNS = intPreferencesKey("home_columns")
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
        val USE_THEMED_ICONS = booleanPreferencesKey("use_themed_icons")
        val ICON_PACK = stringPreferencesKey("icon_pack_package")
        val SEARCH_CONTACTS = booleanPreferencesKey("search_contacts")
        val LEFT_SWIPE_APP_KEY = stringPreferencesKey("left_swipe_app_key")
        val DESKTOP_LOCKED = booleanPreferencesKey("desktop_locked")
        val SHOW_FREQUENT_APPS = booleanPreferencesKey("show_frequent_apps")
        val DRIVE_ENABLED = booleanPreferencesKey("drive_backup_enabled")
        val DRIVE_LAST_TIME = longPreferencesKey("drive_last_backup_time")
        val DRIVE_LAST_HASH = stringPreferencesKey("drive_last_backup_hash")
        val LOCAL_LAST_BACKUP = longPreferencesKey("local_last_backup_time")
        val DRIVE_INTERVAL_DAYS = intPreferencesKey("drive_interval_days")
        val DRIVE_WIFI_ONLY = booleanPreferencesKey("drive_wifi_only")
        val DRIVE_CHARGING_ONLY = booleanPreferencesKey("drive_charging_only")
        val AUTO_UPDATE_ENABLED = booleanPreferencesKey("auto_update_enabled")
        val UPDATE_LAST_CHECK = longPreferencesKey("update_last_check")
        val UPDATE_LAST_NOTIFIED = stringPreferencesKey("update_last_notified_version")
        val APP_LABEL_SCALE = floatPreferencesKey("app_label_scale")
        val APP_LABEL_COLOR = intPreferencesKey("app_label_color")
        val SHOW_STATUS_BAR = booleanPreferencesKey("show_status_bar")
        val HIDE_SYSTEM_STATUS_BAR = booleanPreferencesKey("hide_system_status_bar")
        val STATUS_BAR_SCRIM = floatPreferencesKey("status_bar_scrim")
    }

    companion object {
        /** Valid range for the home/dock/drawer column counts (mirrors the settings steppers). */
        const val MIN_COLUMNS = 3
        const val MAX_COLUMNS = 7

        /** Valid range for the notification-dot scale slider. */
        const val MIN_DOT_SCALE = 0.6f
        const val MAX_DOT_SCALE = 1.8f

        /** Valid range for the app-label text-size slider. */
        const val MIN_LABEL_SCALE = 0.8f
        const val MAX_LABEL_SCALE = 1.6f

        /** Preference keys whose value must be restored as Float (JSON loses the Int/Float distinction). */
        val FLOAT_KEYS = setOf("dock_opacity", "notif_dot_scale", "app_label_scale", "status_bar_scrim")
        val INT_KEYS = setOf("dock_columns", "home_columns", "drawer_columns", "app_label_color")

        /** Device-local bookkeeping keys excluded from an exported backup and preserved across a
         *  restore: Drive enable/last-backup/hash + the local file-backup timestamp. */
        val DRIVE_INTERNAL_KEYS = setOf(
            "drive_backup_enabled", "drive_last_backup_time", "drive_last_backup_hash",
            "local_last_backup_time",
            "drive_interval_days", "drive_wifi_only", "drive_charging_only",
            "auto_update_enabled", "update_last_check", "update_last_notified_version",
        )
    }
}
