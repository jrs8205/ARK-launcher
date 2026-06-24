package org.arkikeskus.launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.arkikeskus.launcher.model.LauncherSettings
import javax.inject.Inject
import javax.inject.Singleton

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
        LauncherSettings(
            dockEnabled = p[Keys.DOCK_ENABLED] ?: true,
            dockColumns = p[Keys.DOCK_COLUMNS] ?: 4,
            homeColumns = p[Keys.HOME_COLUMNS] ?: 4,
            drawerColumns = p[Keys.DRAWER_COLUMNS] ?: 4,
            showDrawerSearch = p[Keys.SHOW_DRAWER_SEARCH] ?: true,
            swipeUpForDrawer = p[Keys.SWIPE_UP_DRAWER] ?: true,
            swipeDownForNotifications = p[Keys.SWIPE_DOWN_NOTIF] ?: true,
            showDockLabels = p[Keys.SHOW_DOCK_LABELS] ?: false,
            showHomeLabels = p[Keys.SHOW_HOME_LABELS] ?: true,
            showDrawerLabels = p[Keys.SHOW_DRAWER_LABELS] ?: true,
            dockBackgroundOpacity = p[Keys.DOCK_OPACITY] ?: 0.35f,
            showPageIndicator = p[Keys.SHOW_PAGE_INDICATOR] ?: true,
            showNotificationDots = p[Keys.SHOW_NOTIF_DOTS] ?: true,
            notificationDotCount = p[Keys.NOTIF_DOT_COUNT] ?: true,
            notificationDotScale = p[Keys.NOTIF_DOT_SCALE] ?: 1.0f,
        )
    }

    /** Ordered list of dock favorite app keys (see AppItem.key). */
    val dockFavorites: Flow<List<String>> = dataStore.data.map { p ->
        p[Keys.DOCK_FAVORITES]?.split("\n")?.filter { it.isNotEmpty() } ?: emptyList()
    }

    suspend fun setDockEnabled(value: Boolean) = edit { it[Keys.DOCK_ENABLED] = value }
    suspend fun setDockColumns(value: Int) = edit { it[Keys.DOCK_COLUMNS] = value }
    suspend fun setHomeColumns(value: Int) = edit { it[Keys.HOME_COLUMNS] = value }
    suspend fun setDrawerColumns(value: Int) = edit { it[Keys.DRAWER_COLUMNS] = value }
    suspend fun setShowDrawerSearch(value: Boolean) = edit { it[Keys.SHOW_DRAWER_SEARCH] = value }
    suspend fun setSwipeUpForDrawer(value: Boolean) = edit { it[Keys.SWIPE_UP_DRAWER] = value }
    suspend fun setSwipeDownForNotifications(value: Boolean) = edit { it[Keys.SWIPE_DOWN_NOTIF] = value }
    suspend fun setShowDockLabels(value: Boolean) = edit { it[Keys.SHOW_DOCK_LABELS] = value }
    suspend fun setShowHomeLabels(value: Boolean) = edit { it[Keys.SHOW_HOME_LABELS] = value }
    suspend fun setShowDrawerLabels(value: Boolean) = edit { it[Keys.SHOW_DRAWER_LABELS] = value }
    suspend fun setDockBackgroundOpacity(value: Float) = edit { it[Keys.DOCK_OPACITY] = value }
    suspend fun setShowPageIndicator(value: Boolean) = edit { it[Keys.SHOW_PAGE_INDICATOR] = value }
    suspend fun setShowNotificationDots(value: Boolean) = edit { it[Keys.SHOW_NOTIF_DOTS] = value }
    suspend fun setNotificationDotCount(value: Boolean) = edit { it[Keys.NOTIF_DOT_COUNT] = value }
    suspend fun setNotificationDotScale(value: Float) = edit { it[Keys.NOTIF_DOT_SCALE] = value }

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
        val SHOW_DOCK_LABELS = booleanPreferencesKey("show_dock_labels")
        val SHOW_HOME_LABELS = booleanPreferencesKey("show_home_labels")
        val SHOW_DRAWER_LABELS = booleanPreferencesKey("show_drawer_labels")
        val DOCK_OPACITY = floatPreferencesKey("dock_opacity")
        val SHOW_PAGE_INDICATOR = booleanPreferencesKey("show_page_indicator")
        val SHOW_NOTIF_DOTS = booleanPreferencesKey("show_notif_dots")
        val NOTIF_DOT_COUNT = booleanPreferencesKey("notif_dot_count")
        val NOTIF_DOT_SCALE = floatPreferencesKey("notif_dot_scale")
    }
}
