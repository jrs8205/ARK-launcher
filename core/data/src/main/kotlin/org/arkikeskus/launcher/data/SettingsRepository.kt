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
import kotlinx.coroutines.flow.onStart
import org.arkikeskus.launcher.model.CustomIcon
import org.arkikeskus.launcher.model.LauncherSettings
import javax.inject.Inject
import javax.inject.Singleton

/** A person the user pinned to the people widget: a quiet tile that stays put between messages.
 *  [key] is [PeopleGrouping.personKey] of [name]; the contact fields are what a lookup found when
 *  the pin was made (blank when the person isn't in the contacts, or contacts aren't allowed). */
data class PinnedPerson(
    val key: String,
    val name: String,
    val lookupUri: String = "",
    val number: String = "",
    val photoUri: String = "",
)

/** A folder shown in the app drawer: a stable [id], a [name], and the keys of its member apps. */
data class DrawerFolder(val id: Long, val name: String, val appKeys: List<String>)

/** How a folder's own icon is filed in [SettingsRepository.folderIcons]: home folders by their row
 *  id, drawer folders by theirs — both ids survive a backup and restore. */
object FolderIconKey {
    fun home(id: Long) = "home:$id"
    fun drawer(id: Long) = "drawer:$id"
    fun isDrawer(key: String) = key.startsWith("drawer:")
}

/**
 * Reads/writes launcher preferences. The [DataStore] is injected (provided from a Context-backed
 * `preferencesDataStore` in DataModule) so the repository's merge logic can be unit-tested on the
 * JVM with a temp-file store.
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    val settings: Flow<LauncherSettings> = dataStore.data.onStart {
        // An older version restores a newer backup by storing the keys it does not know as Long
        // (importRaw's fallback). After the upgrade every registered numeric key must hold its own
        // type before the first typed read, or that read throws on every launch. A stored value of
        // the right type is left alone; a wrong-typed number is converted (the read clamps it); a
        // value that is not a number at all is dropped so the default applies.
        dataStore.edit { p ->
            val stored = p.asMap()
            for (name in FLOAT_KEYS) {
                val raw = stored[floatPreferencesKey(name)] ?: continue
                if (raw !is Float) {
                    p.remove(floatPreferencesKey(name))
                    (raw as? Number)?.let { p[floatPreferencesKey(name)] = it.toFloat() }
                }
            }
            for (name in INT_KEYS) {
                val raw = stored[intPreferencesKey(name)] ?: continue
                if (raw !is Int) {
                    p.remove(intPreferencesKey(name))
                    (raw as? Number)?.let {
                        p[intPreferencesKey(name)] = when (name) {
                            // Page counters clamp before the conversion so a huge Long cannot wrap.
                            Keys.HOME_PAGE.name, Keys.HOME_PAGE_COUNT.name ->
                                it.toLong().coerceIn(0L, HomeLayoutRepository.MAX_PAGES.toLong()).toInt()
                            // An ARGB colour arrives as the signed Int's Long, or its unsigned form: wrap.
                            Keys.APP_LABEL_COLOR.name -> it.toInt()
                            else -> it.toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                        }
                    }
                }
            }
        }
    }.map { p ->
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
            showHomeFolderNames = p[Keys.SHOW_HOME_FOLDER_NAMES] ?: true,
            showDrawerFolderNames = p[Keys.SHOW_DRAWER_FOLDER_NAMES] ?: true,
            dockBackgroundOpacity = (p[Keys.DOCK_OPACITY] ?: 0.35f).coerceIn(0f, 1f),
            showPageIndicator = p[Keys.SHOW_PAGE_INDICATOR] ?: true,
            homePageMarker = (p[Keys.HOME_PAGE_MARKER] ?: LauncherSettings.MARKER_RING)
                .let { if (it == LauncherSettings.MARKER_HOUSE) it else LauncherSettings.MARKER_RING },
            pageSwitchSpeed = (p[Keys.PAGE_SWITCH_SPEED] ?: LauncherSettings.SPEED_NORMAL).let {
                if (it == LauncherSettings.SPEED_FAST || it == LauncherSettings.SPEED_FASTEST) it else LauncherSettings.SPEED_NORMAL
            },
            infiniteScroll = p[Keys.INFINITE_SCROLL] ?: true,
            defaultReminderDismissed = p[Keys.DEFAULT_REMINDER_DISMISSED] ?: false,
            showNotificationDots = p[Keys.SHOW_NOTIF_DOTS] ?: true,
            notificationDotCount = p[Keys.NOTIF_DOT_COUNT] ?: true,
            notificationDotScale = (p[Keys.NOTIF_DOT_SCALE] ?: 1.0f).coerceIn(MIN_DOT_SCALE, MAX_DOT_SCALE),
            useThemedIcons = p[Keys.USE_THEMED_ICONS] ?: false,
            iconPackPackage = p[Keys.ICON_PACK] ?: "",
            searchContacts = p[Keys.SEARCH_CONTACTS] ?: false,
            leftSwipeAppKey = p[Keys.LEFT_SWIPE_APP_KEY] ?: "",
            weatherAppKey = p[Keys.WEATHER_APP_KEY] ?: "",
            desktopLocked = p[Keys.DESKTOP_LOCKED] ?: false,
            showFrequentApps = p[Keys.SHOW_FREQUENT_APPS] ?: false,
            drawerOpensAtTop = p[Keys.DRAWER_OPENS_AT_TOP] ?: true,
            sortDrawerFolders = p[Keys.SORT_DRAWER_FOLDERS] ?: false,
            appLabelTextScale = (p[Keys.APP_LABEL_SCALE] ?: 1.0f).coerceIn(MIN_LABEL_SCALE, MAX_LABEL_SCALE),
            iconScale = (p[Keys.ICON_SCALE] ?: 1.0f).coerceIn(MIN_ICON_SCALE, MAX_ICON_SCALE),
            longPressScale = (p[Keys.LONG_PRESS_SCALE] ?: 1.0f).coerceIn(MIN_LONG_PRESS_SCALE, MAX_LONG_PRESS_SCALE),
            twoLineHomeLabels = p[Keys.TWO_LINE_HOME_LABELS] ?: false,
            twoLineDrawerLabels = p[Keys.TWO_LINE_DRAWER_LABELS] ?: true,
            appLabelColor = p[Keys.APP_LABEL_COLOR] ?: 0xFFFFFFFF.toInt(),
            showStatusBar = p[Keys.SHOW_STATUS_BAR] ?: false,
            showWeather = p[Keys.SHOW_WEATHER] ?: true,
            networkPlaceNames = p[Keys.NETWORK_PLACE_NAMES] ?: false,
            hideSystemStatusBar = p[Keys.HIDE_SYSTEM_STATUS_BAR] ?: false,
            statusBarScrimOpacity = (p[Keys.STATUS_BAR_SCRIM] ?: LauncherSettings.DEFAULT_STATUS_BAR_SCRIM).coerceIn(0f, 1f),
            widgetTonalBackground = p[Keys.WIDGET_TONAL_BACKGROUND] ?: false,
            notificationWidgetCountStyle = (p[Keys.NOTIF_WIDGET_COUNT_STYLE] ?: LauncherSettings.COUNT_NUMBER)
                .let { if (it == LauncherSettings.COUNT_DOT || it == LauncherSettings.COUNT_NONE) it else LauncherSettings.COUNT_NUMBER },
            doubleTapToLock = p[Keys.DOUBLE_TAP_LOCK] ?: false,
            lockSoundsEnabled = p[Keys.LOCK_SOUNDS_ENABLED] ?: false,
            lockSoundUri = p[Keys.LOCK_SOUND_URI].orEmpty(),
            unlockSoundUri = p[Keys.UNLOCK_SOUND_URI].orEmpty(),
            peoplePrivacy = (p[Keys.PEOPLE_PRIVACY] ?: LauncherSettings.PRIVACY_ALL).let {
                if (it == LauncherSettings.PRIVACY_SENDER || it == LauncherSettings.PRIVACY_COUNT) it
                else LauncherSettings.PRIVACY_ALL
            },
            peopleBatchEnabled = p[Keys.PEOPLE_BATCH_ENABLED] ?: false,
            peopleShowApps = p[Keys.PEOPLE_SHOW_APPS] ?: true,
            homePage = (p[Keys.HOME_PAGE] ?: 0).coerceIn(0, HomeLayoutRepository.MAX_PAGES),
            homePageCount = (p[Keys.HOME_PAGE_COUNT] ?: 0).coerceIn(0, HomeLayoutRepository.MAX_PAGES),
            peopleBatchTimes = (p[Keys.PEOPLE_BATCH_TIMES] ?: LauncherSettings.DEFAULT_BATCH_TIMES)
                .let { BatchSchedule.parse(it) }
                .let { if (it.isEmpty()) LauncherSettings.DEFAULT_BATCH_TIMES else BatchSchedule.format(it) },
        )
    }

    suspend fun setPeopleBatchEnabled(value: Boolean) = edit { it[Keys.PEOPLE_BATCH_ENABLED] = value }
    /** The page HOME returns to and the explicit page count, in one settings snapshot (the page
     *  menu's operations own both; see WorkspacePageOperations). */
    suspend fun setHomePages(home: Int, count: Int) = edit {
        it[Keys.HOME_PAGE] = home.coerceIn(0, HomeLayoutRepository.MAX_PAGES - 1)
        it[Keys.HOME_PAGE_COUNT] = count.coerceIn(0, HomeLayoutRepository.MAX_PAGES)
    }

    suspend fun setPeopleShowApps(value: Boolean) = edit { it[Keys.PEOPLE_SHOW_APPS] = value }

    /** Batch delivery times; normalized through [BatchSchedule], falling back to the default when
     *  nothing parses so the batch can never silently hold messages forever. */
    suspend fun setPeopleBatchTimes(raw: String) = edit { p ->
        val parsed = BatchSchedule.parse(raw)
        p[Keys.PEOPLE_BATCH_TIMES] = if (parsed.isEmpty()) LauncherSettings.DEFAULT_BATCH_TIMES else BatchSchedule.format(parsed)
    }

    // --- People widget: local batch deadlines (also reads legacy Android snoozes) -------------
    // "key\tdeliverAtEpochMs" per line. Device-local: a notification key means nothing elsewhere.

    /** Notification key → local delivery time. Notification text is never persisted. */
    val heldNotifications: Flow<Map<String, Long>> = dataStore.data.map { p ->
        p[Keys.PEOPLE_HELD]?.split("\n")?.filter { it.isNotEmpty() }?.mapNotNull { line ->
            val i = line.indexOf('\t')
            val until = if (i > 0) line.substring(i + 1).toLongOrNull() else null
            if (until == null) null else line.substring(0, i) to until
        }?.toMap() ?: emptyMap()
    }

    suspend fun setHeldNotifications(held: Map<String, Long>) = edit { p ->
        p[Keys.PEOPLE_HELD] = held.entries.joinToString("\n") { (k, v) -> k.replace(SEPARATORS, " ") + "\t" + v }
    }

    // --- People widget aliases ("this notification name is the same person") --------------------
    // One link per line: "aliasKey\ttargetKey" (both PeopleGrouping.personKey values).

    /** alias key → target key. Single-hop: a target is never itself an alias (see [linkPerson]). */
    val peopleAliases: Flow<Map<String, String>> = dataStore.data.map { p -> parseAliases(p[Keys.PEOPLE_ALIASES]) }

    /** Links [alias] to [target]: an existing link on [alias] is replaced, links that pointed at
     *  [alias] follow it to the new target, and a target that is itself an alias resolves first. */
    suspend fun linkPerson(alias: String, target: String) = edit { p ->
        val current = parseAliases(p[Keys.PEOPLE_ALIASES])
        val resolved = current[target] ?: target
        if (resolved == alias) return@edit
        val next = current
            .mapValues { (_, t) -> if (t == alias) resolved else t }
            .filterKeys { it != alias && it != resolved } + (alias to resolved)
        p[Keys.PEOPLE_ALIASES] = serializeAliases(next.filter { (a, t) -> a != t })
    }

    /** Removes every link that merges into [target]. */
    suspend fun unlinkPerson(target: String) = edit { p ->
        p[Keys.PEOPLE_ALIASES] = serializeAliases(parseAliases(p[Keys.PEOPLE_ALIASES]).filterValues { it != target })
    }

    private fun parseAliases(raw: String?): Map<String, String> =
        raw?.split("\n")?.filter { it.isNotEmpty() }?.mapNotNull { line ->
            val i = line.indexOf('\t')
            if (i <= 0 || i == line.length - 1) null else line.substring(0, i) to line.substring(i + 1)
        }?.toMap() ?: emptyMap()

    private fun serializeAliases(aliases: Map<String, String>): String =
        aliases.entries.joinToString("\n") { (a, t) -> a.replace(SEPARATORS, " ") + "\t" + t.replace(SEPARATORS, " ") }

    /** What the people widget shows of a notification (see LauncherSettings.PRIVACY_*). */
    suspend fun setPeoplePrivacy(value: String) = edit { it[Keys.PEOPLE_PRIVACY] = value }

    // --- People widget pins ---------------------------------------------------------------------
    // One person per line, tab-separated: "key\tname\tlookupUri\tnumber\tphotoUri". Names are
    // normalized at the write point like folder names, so a plain split round-trips.

    /** People pinned to the people widget, in pin order. */
    val pinnedPeople: Flow<List<PinnedPerson>> = dataStore.data.map { p -> parsePinned(p[Keys.PINNED_PEOPLE]) }

    /** Pins [person] (replacing an earlier pin with the same key, keeping its position). */
    suspend fun pinPerson(person: PinnedPerson) = edit { p ->
        val current = parsePinned(p[Keys.PINNED_PEOPLE])
        val next = if (current.any { it.key == person.key }) current.map { if (it.key == person.key) person else it }
            else current + person
        p[Keys.PINNED_PEOPLE] = serializePinned(next)
    }

    suspend fun unpinPerson(key: String) = edit { p ->
        p[Keys.PINNED_PEOPLE] = serializePinned(parsePinned(p[Keys.PINNED_PEOPLE]).filterNot { it.key == key })
    }

    private fun parsePinned(raw: String?): List<PinnedPerson> =
        raw?.split("\n")?.filter { it.isNotEmpty() }?.mapNotNull { line ->
            val parts = line.split('\t')
            val key = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PinnedPerson(
                key = key,
                name = parts.getOrNull(1).orEmpty().ifBlank { key },
                lookupUri = parts.getOrNull(2).orEmpty(),
                number = parts.getOrNull(3).orEmpty(),
                photoUri = parts.getOrNull(4).orEmpty(),
            )
        } ?: emptyList()

    private fun serializePinned(people: List<PinnedPerson>): String =
        people.joinToString("\n") { person ->
            listOf(person.key, person.name, person.lookupUri, person.number, person.photoUri)
                .joinToString("\t") { it.replace(SEPARATORS, " ") }
        }

    suspend fun setDoubleTapToLock(value: Boolean) = edit { it[Keys.DOUBLE_TAP_LOCK] = value }
    suspend fun setLockSoundsEnabled(value: Boolean) = edit { it[Keys.LOCK_SOUNDS_ENABLED] = value }
    suspend fun setLockSoundUri(uri: String) = edit { it[Keys.LOCK_SOUND_URI] = uri }
    suspend fun setUnlockSoundUri(uri: String) = edit { it[Keys.UNLOCK_SOUND_URI] = uri }

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

    /** Icons the user picked for single apps (AppItem.key -> icon), shown in place of their own. */
    val customIcons: Flow<Map<String, CustomIcon>> = dataStore.data.map { p -> parseIcons(p[Keys.CUSTOM_ICONS]) }

    /** Sets (or, for null, clears) the icon picked for the app [key]. */
    suspend fun setCustomIcon(key: String, icon: CustomIcon?) = edit { p ->
        p[Keys.CUSTOM_ICONS] = serializeIcons(parseIcons(p[Keys.CUSTOM_ICONS]).toMutableMap().apply {
            if (icon == null) remove(key) else put(key, icon)
        })
    }

    /** Icons the user picked for folders ([FolderIconKey] -> icon), shown in place of the preview. */
    val folderIcons: Flow<Map<String, CustomIcon>> = dataStore.data.map { p -> parseIcons(p[Keys.FOLDER_ICONS]) }

    /** Sets (or, for null, clears) the icon picked for the folder [key] (see [FolderIconKey]). */
    suspend fun setFolderIcon(key: String, icon: CustomIcon?) = edit { p ->
        p[Keys.FOLDER_ICONS] = serializeIcons(parseIcons(p[Keys.FOLDER_ICONS]).toMutableMap().apply {
            if (icon == null) remove(key) else put(key, icon)
        })
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

    /** Creates an empty drawer folder, returns its new id. A deleted folder's id comes back this way,
     *  so an icon still filed under it is dropped: a new folder starts with its preview. */
    suspend fun createDrawerFolder(name: String): Long {
        var newId = 1L
        edit { p ->
            val folders = parseFolders(p[Keys.DRAWER_FOLDERS])
            newId = (folders.maxOfOrNull { it.id } ?: 0L) + 1L
            p[Keys.DRAWER_FOLDERS] = serializeFolders(folders + DrawerFolder(newId, name, emptyList()))
            dropFolderIcon(p, FolderIconKey.drawer(newId))
        }
        return newId
    }

    suspend fun renameDrawerFolder(id: Long, name: String) = editFolders { folders ->
        folders.map { if (it.id == id) it.copy(name = name) else it }
    }

    /** Deletes a drawer folder and its icon (an image file stays: see CustomIconStore.deleteDrawerFolder). */
    suspend fun deleteDrawerFolder(id: Long) = edit { p ->
        p[Keys.DRAWER_FOLDERS] = serializeFolders(parseFolders(p[Keys.DRAWER_FOLDERS]).filterNot { it.id == id })
        dropFolderIcon(p, FolderIconKey.drawer(id))
    }

    private fun dropFolderIcon(p: MutablePreferences, key: String) {
        val icons = parseIcons(p[Keys.FOLDER_ICONS])
        if (key in icons) p[Keys.FOLDER_ICONS] = serializeIcons(icons - key)
    }

    suspend fun addAppsToDrawerFolder(id: Long, keys: List<String>) = editFolders { folders ->
        folders.map { f ->
            if (f.id == id) f.copy(appKeys = (f.appKeys + keys).distinct()) else f
        }
    }

    suspend fun removeAppFromDrawerFolder(id: Long, key: String) = editFolders { folders ->
        folders.map { f -> if (f.id == id) f.copy(appKeys = f.appKeys - key) else f }
    }

    /** Moves a drawer folder [by] places among the folders (negative = earlier), stopping at the ends. */
    suspend fun moveDrawerFolder(id: Long, by: Int) = editFolders { folders ->
        val from = folders.indexOfFirst { it.id == id }
        if (from < 0) return@editFolders folders
        val to = (from + by).coerceIn(0, folders.lastIndex)
        if (to == from) folders else folders.toMutableList().apply { add(to, removeAt(from)) }
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
    suspend fun setShowHomeFolderNames(value: Boolean) = edit { it[Keys.SHOW_HOME_FOLDER_NAMES] = value }
    suspend fun setShowDrawerFolderNames(value: Boolean) = edit { it[Keys.SHOW_DRAWER_FOLDER_NAMES] = value }
    suspend fun setDockBackgroundOpacity(value: Float) = edit { it[Keys.DOCK_OPACITY] = value.coerceIn(0f, 1f) }
    suspend fun setShowPageIndicator(value: Boolean) = edit { it[Keys.SHOW_PAGE_INDICATOR] = value }
    /** Home-page marker style of the page dots (see LauncherSettings.MARKER_*). */
    suspend fun setHomePageMarker(style: String) = edit { it[Keys.HOME_PAGE_MARKER] = style }

    /** How fast the home pager settles on a page ([LauncherSettings.SPEED_NORMAL] / FAST / FASTEST). */
    suspend fun setPageSwitchSpeed(speed: String) = edit { it[Keys.PAGE_SWITCH_SPEED] = speed }
    suspend fun setInfiniteScroll(value: Boolean) = edit { it[Keys.INFINITE_SCROLL] = value }
    suspend fun setShowNotificationDots(value: Boolean) = edit { it[Keys.SHOW_NOTIF_DOTS] = value }
    suspend fun setNotificationDotCount(value: Boolean) = edit { it[Keys.NOTIF_DOT_COUNT] = value }
    suspend fun setNotificationDotScale(value: Float) =
        edit { it[Keys.NOTIF_DOT_SCALE] = value.coerceIn(MIN_DOT_SCALE, MAX_DOT_SCALE) }
    suspend fun setUseThemedIcons(value: Boolean) = edit { it[Keys.USE_THEMED_ICONS] = value }

    /** Sets (or clears, for blank) the selected third-party icon pack package. */
    suspend fun setIconPackPackage(pkg: String) = edit { it[Keys.ICON_PACK] = pkg.trim() }

    /** Count indicator style of the built-in notifications widget (see LauncherSettings.COUNT_*). */
    suspend fun setWidgetTonalBackground(value: Boolean) = edit { it[Keys.WIDGET_TONAL_BACKGROUND] = value }
    suspend fun setNotificationWidgetCountStyle(style: String) = edit { it[Keys.NOTIF_WIDGET_COUNT_STYLE] = style }
    suspend fun setSearchContacts(value: Boolean) = edit { it[Keys.SEARCH_CONTACTS] = value }

    /** Sets (or, for a blank/null [key], clears) the app launched by the left-edge home swipe. */
    suspend fun setLeftSwipeAppKey(key: String?) = edit { it[Keys.LEFT_SWIPE_APP_KEY] = key?.trim().orEmpty() }

    /** Sets (or, for a blank/null [key], clears) the app a tap on the weather line opens. */
    suspend fun setWeatherAppKey(key: String?) = edit { it[Keys.WEATHER_APP_KEY] = key?.trim().orEmpty() }

    /** Locks/unlocks the desktop layout (blocks moving/removing/adding home + dock items). */
    suspend fun setDesktopLocked(value: Boolean) = edit { it[Keys.DESKTOP_LOCKED] = value }

    /** Toggles the "most used" row in the app drawer. */
    suspend fun setShowFrequentApps(value: Boolean) = edit { it[Keys.SHOW_FREQUENT_APPS] = value }

    /** Whether the app drawer reopens at the top vs. remembers its last scroll position. */
    suspend fun setDrawerOpensAtTop(value: Boolean) = edit { it[Keys.DRAWER_OPENS_AT_TOP] = value }
    suspend fun setSortDrawerFolders(value: Boolean) = edit { it[Keys.SORT_DRAWER_FOLDERS] = value }

    /** Size multiplier for app icon labels (clamped to the slider's range). */
    suspend fun setAppLabelTextScale(value: Float) =
        edit { it[Keys.APP_LABEL_SCALE] = value.coerceIn(MIN_LABEL_SCALE, MAX_LABEL_SCALE) }

    /** Size multiplier for app icons (clamped to the slider's range). */
    suspend fun setIconScale(value: Float) =
        edit { it[Keys.ICON_SCALE] = value.coerceIn(MIN_ICON_SCALE, MAX_ICON_SCALE) }

    /** Multiplier on the system long-press timeout (clamped to the slider's range). */
    suspend fun setLongPressScale(value: Float) =
        edit { it[Keys.LONG_PRESS_SCALE] = value.coerceIn(MIN_LONG_PRESS_SCALE, MAX_LONG_PRESS_SCALE) }

    /** ARGB color for the home-surface app icon labels. */
    suspend fun setAppLabelColor(argb: Int) = edit { it[Keys.APP_LABEL_COLOR] = argb }

    /** Lets home-screen labels wrap onto a second line (apps, folders, shortcuts; not the dock). */
    suspend fun setTwoLineHomeLabels(value: Boolean) = edit { it[Keys.TWO_LINE_HOME_LABELS] = value }

    /** Lets app-drawer labels wrap onto a second line. */
    suspend fun setTwoLineDrawerLabels(value: Boolean) = edit { it[Keys.TWO_LINE_DRAWER_LABELS] = value }

    /** Shows/hides the home status bar (clock + battery + signal). */
    suspend fun setShowStatusBar(value: Boolean) = edit { it[Keys.SHOW_STATUS_BAR] = value }

    suspend fun setShowWeather(value: Boolean) = edit { it[Keys.SHOW_WEATHER] = value }

    suspend fun setNetworkPlaceNames(value: Boolean) = edit { it[Keys.NETWORK_PLACE_NAMES] = value }

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

    /** Drops every dock favourite of (package, profile): the app was uninstalled. */
    suspend fun removeDockPackage(packageName: String, userSerial: Long) = edit { p ->
        val current = currentFavorites(p)
        val kept = current.filterNot { key ->
            val parts = key.split('/')
            parts.size == 3 && parts[0] == packageName && parts[2].toLongOrNull() == userSerial
        }
        if (kept.size != current.size) p[Keys.DOCK_FAVORITES] = kept.joinToString("\n")
    }

    /**
     * Inserts [key] into the dock favorites at [index] (clamped), used when an icon is dragged into
     * the dock. Any existing occurrence is removed first, so this also re-positions a key already in
     * the dock and never creates duplicates.
     *
     * The dock shows only favorites whose app resolves, so the drop index counts those. Given the
     * [resolvableKeys], [index] is read in that shown order and the key lands right before the shown
     * favorite at that position (or right after the last shown one); without it, [index] is a raw
     * position and uninstalled favorites in between shift the drop.
     */
    suspend fun addToDockAt(key: String, index: Int, resolvableKeys: Set<String>? = null) = edit { p ->
        val current = currentFavorites(p).toMutableList()
        current.remove(key)
        val at = if (resolvableKeys == null) {
            index.coerceIn(0, current.size)
        } else {
            val shown = current.indices.filter { current[it] in resolvableKeys }
            val i = index.coerceIn(0, shown.size)
            if (i < shown.size) shown[i] else (shown.lastOrNull()?.plus(1) ?: 0)
        }
        current.add(at, key)
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

    /** Every app key the settings refer to: dock, hidden apps, custom labels, drawer folders and the
     *  left-swipe app (see [APP_KEY_PREFS]). */
    suspend fun referencedAppKeys(): Set<String> {
        val prefs = dataStore.data.first()
        val keys = LinkedHashSet<String>()
        for (name in APP_KEY_PREFS) {
            val raw = prefs[stringPreferencesKey(name)] ?: continue
            rewriteAppKeys(name, raw) { keys += it; it }
        }
        return keys
    }

    /** Replaces app keys per [renames] in every setting that refers to apps, in one write; a renamed
     *  entry that meets its new key collapses into it (see [rewriteAppKeys]). */
    suspend fun renameAppKeys(renames: Map<String, String>) {
        if (renames.isEmpty()) return
        edit { p ->
            for (name in APP_KEY_PREFS) {
                val key = stringPreferencesKey(name)
                val raw = p[key] ?: continue
                p[key] = rewriteAppKeys(name, raw) { renames[it] ?: it }
            }
        }
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
    /** "Not now" on the home screen's set-as-default card. */
    suspend fun dismissDefaultReminder() = edit { it[Keys.DEFAULT_REMINDER_DISMISSED] = true }

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

    /** The dock as versions before 0.9.7 stored it: a favourites key list. Read once at start-up
     *  (and from an older backup file) to turn it into dock rows; empty once migrated. */
    suspend fun legacyDockFavorites(): List<String> =
        dataStore.data.first()[Keys.DOCK_FAVORITES]?.split("\n")?.filter { it.isNotEmpty() } ?: emptyList()

    /** Drops the favourites key list once its rows exist (see [legacyDockFavorites]). */
    suspend fun clearLegacyDockFavorites() = edit { it.remove(Keys.DOCK_FAVORITES) }

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
            .mapNotNull { (k, v) ->
                // A gallery image is a file on this phone: only icon-pack icons go in the backup.
                if (k.name in ICON_PREFS && v is String) {
                    serializeIcons(parseIcons(v).filterValues { it is CustomIcon.FromPack }).takeIf { it.isNotEmpty() }?.let { k.name to it }
                } else {
                    k.name to v
                }
            }
            .toMap()

    /**
     * Replaces all preferences with [values]. Known keys are written only with their registered
     * type (see [BOOLEAN_KEYS]/[STRING_KEYS]/[FLOAT_KEYS]/[INT_KEYS]); JSON collapses Int/Float
     * into "number", so numeric values are coerced back by the registry; unknown keys fall back
     * to their JSON type.
     *
     * Device-local state (the local file-backup time, first-run/onboarding flags, batch-held
     * notification keys and usage stats) is snapshotted before the clear and re-applied afterward,
     * so restoring a backup never wipes this device's local state.
     */
    suspend fun importRaw(values: Map<String, Any>) {
        dataStore.edit { prefs ->
            // Snapshot device-local bookkeeping before clearing.
            val localLastBackup = prefs[Keys.LOCAL_LAST_BACKUP]
            val layoutSeeded = prefs[Keys.DEFAULT_LAYOUT_SEEDED]
            val onboardingDone = prefs[Keys.ONBOARDING_DONE]
            val reminderDismissed = prefs[Keys.DEFAULT_REMINDER_DISMISSED]
            val firstRunFresh = prefs[Keys.FIRST_RUN_FRESH]
            val heldNotifications = prefs[Keys.PEOPLE_HELD]
            // The picked sounds' file permissions belong to this phone (see DEVICE_LOCAL_KEYS).
            val lockSound = prefs[Keys.LOCK_SOUND_URI]
            val unlockSound = prefs[Keys.UNLOCK_SOUND_URI]
            // Device-local usage stats aren't in the backup (see exportRaw) — preserve this device's.
            val appUsage = prefs[stringPreferencesKey(AppUsageRepository.USAGE_KEY)]
            // Gallery images are files on this phone (not in a backup): keep them for the apps and
            // home folders the backup picks no icon for. Not for drawer folders: their ids come back
            // (max + 1), so the folder the backup has under an id may not be the one with the picture.
            // A picture left unused this way goes at the next start (CustomIconStore.deleteUnusedPictures).
            val localImages = ICON_PREFS.associateWith { name ->
                parseIcons(prefs[stringPreferencesKey(name)]).filter { (key, icon) ->
                    icon is CustomIcon.FromFile && !(name == Keys.FOLDER_ICONS.name && FolderIconKey.isDrawer(key))
                }
            }
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
            if (reminderDismissed != null) prefs[Keys.DEFAULT_REMINDER_DISMISSED] = reminderDismissed
            if (firstRunFresh != null) prefs[Keys.FIRST_RUN_FRESH] = firstRunFresh
            if (heldNotifications != null) prefs[Keys.PEOPLE_HELD] = heldNotifications
            if (lockSound != null) prefs[Keys.LOCK_SOUND_URI] = lockSound
            if (unlockSound != null) prefs[Keys.UNLOCK_SOUND_URI] = unlockSound
            if (appUsage != null) prefs[stringPreferencesKey(AppUsageRepository.USAGE_KEY)] = appUsage
            for ((name, images) in localImages) {
                if (images.isEmpty()) continue
                val key = stringPreferencesKey(name)
                val restored = parseIcons(prefs[key])
                prefs[key] = serializeIcons(images.filterKeys { it !in restored } + restored)
            }
        }
    }

    /** Every stored preference exactly as it is, for [rollbackImport] (a failed restore's rollback;
     *  [importRaw] can't put back keys it doesn't know with their original types). */
    internal suspend fun snapshot(): Preferences = dataStore.data.first()

    /**
     * Undoes an [importRaw] whose layout write then failed: every key still holding the value the
     * import left ([imported]) goes back to its [before] value, or away. A key written since — the
     * notification listener saving a held notification meanwhile, say — keeps its newer value;
     * putting the whole old snapshot back silently discarded it.
     */
    internal suspend fun rollbackImport(before: Preferences, imported: Preferences) {
        dataStore.edit { prefs ->
            for (key in before.asMap().keys + imported.asMap().keys) {
                @Suppress("UNCHECKED_CAST")
                val k = key as Preferences.Key<Any>
                if (prefs[k] != imported[k]) continue
                val old = before[k]
                if (old == null) prefs.remove(k) else prefs[k] = old
            }
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
        val CUSTOM_ICONS = stringPreferencesKey("custom_icons")
        val FOLDER_ICONS = stringPreferencesKey("folder_icons")
        val DRAWER_FOLDERS = stringPreferencesKey("drawer_folders")
        val SHOW_DOCK_LABELS = booleanPreferencesKey("show_dock_labels")
        val SHOW_HOME_LABELS = booleanPreferencesKey("show_home_labels")
        val SHOW_DRAWER_LABELS = booleanPreferencesKey("show_drawer_labels")
        val SHOW_HOME_FOLDER_NAMES = booleanPreferencesKey("show_home_folder_names")
        val SHOW_DRAWER_FOLDER_NAMES = booleanPreferencesKey("show_drawer_folder_names")
        val DOCK_OPACITY = floatPreferencesKey("dock_opacity")
        val SHOW_PAGE_INDICATOR = booleanPreferencesKey("show_page_indicator")
        val HOME_PAGE_MARKER = stringPreferencesKey("home_page_marker")
        val PAGE_SWITCH_SPEED = stringPreferencesKey("page_switch_speed")
        val INFINITE_SCROLL = booleanPreferencesKey("infinite_scroll")
        val SHOW_NOTIF_DOTS = booleanPreferencesKey("show_notif_dots")
        val NOTIF_DOT_COUNT = booleanPreferencesKey("notif_dot_count")
        val NOTIF_DOT_SCALE = floatPreferencesKey("notif_dot_scale")
        val WIDGET_TONAL_BACKGROUND = booleanPreferencesKey("widget_tonal_background")
        val NOTIF_WIDGET_COUNT_STYLE = stringPreferencesKey("notif_widget_count_style")
        val USE_THEMED_ICONS = booleanPreferencesKey("use_themed_icons")
        val ICON_PACK = stringPreferencesKey("icon_pack_package")
        val SEARCH_CONTACTS = booleanPreferencesKey("search_contacts")
        val LEFT_SWIPE_APP_KEY = stringPreferencesKey("left_swipe_app_key")
        val WEATHER_APP_KEY = stringPreferencesKey("weather_app_key")
        val DESKTOP_LOCKED = booleanPreferencesKey("desktop_locked")
        val DOUBLE_TAP_LOCK = booleanPreferencesKey("double_tap_lock")
        val LOCK_SOUNDS_ENABLED = booleanPreferencesKey("lock_sounds_enabled")
        val LOCK_SOUND_URI = stringPreferencesKey("lock_sound_uri")
        val UNLOCK_SOUND_URI = stringPreferencesKey("unlock_sound_uri")
        val SHOW_FREQUENT_APPS = booleanPreferencesKey("show_frequent_apps")
        val DRAWER_OPENS_AT_TOP = booleanPreferencesKey("drawer_opens_at_top")
        val SORT_DRAWER_FOLDERS = booleanPreferencesKey("sort_drawer_folders")
        val LOCAL_LAST_BACKUP = longPreferencesKey("local_last_backup_time")
        val APP_LABEL_SCALE = floatPreferencesKey("app_label_scale")
        val ICON_SCALE = floatPreferencesKey("icon_scale")
        val LONG_PRESS_SCALE = floatPreferencesKey("long_press_scale")
        val APP_LABEL_COLOR = intPreferencesKey("app_label_color")
        val TWO_LINE_HOME_LABELS = booleanPreferencesKey("two_line_home_labels")
        val TWO_LINE_DRAWER_LABELS = booleanPreferencesKey("two_line_drawer_labels")
        val SHOW_STATUS_BAR = booleanPreferencesKey("show_status_bar")
        val SHOW_WEATHER = booleanPreferencesKey("show_weather")
        val NETWORK_PLACE_NAMES = booleanPreferencesKey("network_place_names")
        val HIDE_SYSTEM_STATUS_BAR = booleanPreferencesKey("hide_system_status_bar")
        val STATUS_BAR_SCRIM = floatPreferencesKey("status_bar_scrim")
        val DEFAULT_LAYOUT_SEEDED = booleanPreferencesKey("default_layout_seeded")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val DEFAULT_REMINDER_DISMISSED = booleanPreferencesKey("default_reminder_dismissed")
        val FIRST_RUN_FRESH = stringPreferencesKey("first_run_fresh")
        val PEOPLE_PRIVACY = stringPreferencesKey("people_privacy")
        val PINNED_PEOPLE = stringPreferencesKey("pinned_people")
        val PEOPLE_ALIASES = stringPreferencesKey("people_aliases")
        val PEOPLE_BATCH_ENABLED = booleanPreferencesKey("people_batch_enabled")
        val PEOPLE_BATCH_TIMES = stringPreferencesKey("people_batch_times")
        val PEOPLE_SHOW_APPS = booleanPreferencesKey("people_show_apps")
        val PEOPLE_HELD = stringPreferencesKey("people_held_notifications")
        val HOME_PAGE = intPreferencesKey("home_page")
        val HOME_PAGE_COUNT = intPreferencesKey("home_page_count")
    }

    companion object {
        /** Valid range for the home/dock/drawer column counts (mirrors the settings steppers). */
        const val MIN_COLUMNS = 3
        const val MAX_COLUMNS = 7

        /** Valid range for the home-grid row count (mirrors the settings stepper). */
        const val MIN_ROWS = 5
        const val MAX_ROWS = 10

        /** Valid range for the notification-dot scale slider. */
        const val MIN_DOT_SCALE = 0.6f
        const val MAX_DOT_SCALE = 1.8f

        /** Valid range for the app-label text-size slider. */
        const val MIN_LABEL_SCALE = 0.8f
        const val MAX_LABEL_SCALE = 1.6f

        /** Valid range for the icon-size slider. */
        const val MIN_ICON_SCALE = 0.8f
        const val MAX_ICON_SCALE = 1.3f

        /** Valid range for the long-press delay slider (a multiplier on the system timeout). */
        const val MIN_LONG_PRESS_SCALE = 0.5f
        const val MAX_LONG_PRESS_SCALE = 2.0f

        /** Preference keys whose value must be restored as Float (JSON loses the Int/Float distinction). */
        val FLOAT_KEYS = setOf(
            "dock_opacity", "notif_dot_scale", "app_label_scale", "icon_scale", "status_bar_scrim", "long_press_scale",
        )
        val INT_KEYS = setOf(
            "dock_columns", "home_columns", "home_rows", "drawer_columns", "app_label_color",
            "home_page", "home_page_count",
        )

        /** Known boolean/string preference keys. importRaw writes a known key ONLY with its
         *  registered type — a wrong-typed value in an edited/corrupted backup would otherwise be
         *  stored under the same key name and crash every settings read with a ClassCastException. */
        val BOOLEAN_KEYS = setOf(
            "dock_enabled", "show_drawer_search", "swipe_up_drawer", "swipe_down_notif",
            "show_dock_labels", "show_home_labels", "show_drawer_labels", "show_page_indicator",
            "show_notif_dots", "notif_dot_count", "use_themed_icons", "search_contacts",
            "desktop_locked", "show_frequent_apps", "drawer_opens_at_top", "show_status_bar",
            "show_weather", "hide_system_status_bar", "double_tap_lock", "widget_tonal_background",
            "two_line_home_labels", "two_line_drawer_labels", "people_batch_enabled", "people_show_apps",
            "network_place_names", "infinite_scroll", "lock_sounds_enabled", "sort_drawer_folders",
            "show_home_folder_names", "show_drawer_folder_names",
        )
        val STRING_KEYS = setOf(
            "dock_favorites", "hidden_apps", "custom_labels", "drawer_folders",
            "notif_widget_count_style", "icon_pack_package", "left_swipe_app_key", "weather_app_key", "home_page_marker",
            "page_switch_speed",
            "people_privacy", "pinned_people", "people_aliases", "people_batch_times",
            "custom_icons", "folder_icons",
        )

        /** Preferences holding picked icons ("key\ticon" lines, see [CustomIcon.encode]). */
        val ICON_PREFS = setOf("custom_icons", "folder_icons")

        private fun parseIcons(raw: String?): Map<String, CustomIcon> =
            raw?.split("\n")?.filter { it.isNotEmpty() }?.mapNotNull { line ->
                val i = line.indexOf('\t')
                if (i <= 0) null else CustomIcon.decode(line.substring(i + 1))?.let { line.substring(0, i) to it }
            }?.toMap() ?: emptyMap()

        private fun serializeIcons(icons: Map<String, CustomIcon>): String =
            icons.entries.joinToString("\n") { "${it.key}\t${it.value.encode()}" }

        /** Preferences whose values hold app keys ("package/class/userSerial", AppItem.key). */
        val APP_KEY_PREFS = setOf(
            "dock_favorites", "hidden_apps", "custom_labels", "custom_icons", "drawer_folders", "left_swipe_app_key", "weather_app_key",
        )

        /**
         * Rewrites each app key inside the value [raw] of the [APP_KEY_PREFS] preference [name] with
         * [transform]; null drops that entry (a dock or hidden entry, a label line, a drawer-folder
         * member, the left-swipe or weather app). Duplicates a rewrite creates collapse into the first one.
         * Folders that end up empty stay (an empty drawer folder is a normal state), and any other
         * preference comes back unchanged.
         */
        fun rewriteAppKeys(name: String, raw: String, transform: (String) -> String?): String {
            val lines = raw.split("\n").filter { it.isNotEmpty() }
            return when (name) {
                "dock_favorites", "hidden_apps" -> lines.mapNotNull(transform).distinct().joinToString("\n")
                "custom_labels", "custom_icons" -> lines.mapNotNull { line ->
                    val tab = line.indexOf('\t')
                    if (tab <= 0) line else transform(line.substring(0, tab))?.let { it + line.substring(tab) }
                }.distinctBy { it.substringBefore('\t') }.joinToString("\n")
                "drawer_folders" -> lines.joinToString("\n") { line ->
                    val fields = line.split('\t')
                    if (fields.size <= 2) line
                    else (fields.take(2) + fields.drop(2).filter { it.isNotEmpty() }.mapNotNull(transform).distinct())
                        .joinToString("\t")
                }
                "left_swipe_app_key", "weather_app_key" -> if (raw.isBlank()) raw else transform(raw.trim()).orEmpty()
                else -> raw
            }
        }

        /** Device-local bookkeeping keys excluded from an exported backup and never imported.
         *  The `drive_*`/`update_*` names are ≤0.7.11 leftovers (the Drive backup and in-app
         *  updater were removed in 0.7.12): upgraded devices still carry them in the DataStore and
         *  old backup files still contain them, so the names must stay blocked here. */
        val DEVICE_LOCAL_KEYS = setOf(
            "drive_backup_enabled", "drive_last_backup_time", "drive_last_backup_hash",
            "drive_failure_count", "local_last_backup_time",
            "drive_interval_days", "drive_wifi_only", "drive_charging_only",
            "auto_update_enabled", "update_last_check", "update_last_notified_version",
            "default_layout_seeded", "onboarding_done", "first_run_fresh", "people_held_notifications",
            "default_reminder_dismissed",
            // A picked lock/unlock sound is a content URI this phone holds a read grant for; another
            // phone could not play it.
            "lock_sound_uri", "unlock_sound_uri",
        )
    }
}
