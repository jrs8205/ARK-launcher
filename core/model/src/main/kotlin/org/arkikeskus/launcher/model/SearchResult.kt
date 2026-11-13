package org.arkikeskus.launcher.model

/**
 * One match from a search source. Kept free of Android `Intent` plumbing so it stays comparable and
 * JVM-testable; the UI builds the actual Intent from [Setting.action] / [Contact.lookupUri] / number.
 */
sealed interface SearchResult {
    val id: String

    /** [privateProfile]: the app is one of the private space's (found only while the space is unlocked). */
    data class App(val app: AppItem, val privateProfile: Boolean = false) : SearchResult {
        override val id: String get() = "app:${app.key}"
    }

    /** The private space itself: the row that unlocks it if needed and shows its tab. */
    data object PrivateSpaceEntry : SearchResult {
        override val id: String get() = "private-space"
    }

    /** A system settings page. [action] is a `Settings.ACTION_*` (or equivalent) intent action. */
    data class Setting(val title: String, val action: String, val key: String) : SearchResult {
        override val id: String get() = "setting:$key"
    }

    data class Contact(
        val name: String,
        val lookupUri: String,
        val number: String?,
        val photoUri: String?,
        val contactId: Long,
    ) : SearchResult {
        override val id: String get() = "contact:$contactId"
    }

    data class Calculation(val expression: String, val result: String) : SearchResult {
        override val id: String get() = "calc"
    }
}

/**
 * Search results grouped into the sections the drawer renders (top→bottom: calc, apps, settings, contacts).
 * [privateApps] are the private space's matches, kept apart from [apps] (they are started and drawn their own
 * way); [privateSpaceEntry] says the private space's own row is offered.
 */
data class SearchResults(
    val calc: SearchResult.Calculation? = null,
    val apps: List<AppItem> = emptyList(),
    val settings: List<SearchResult.Setting> = emptyList(),
    val contacts: List<SearchResult.Contact> = emptyList(),
    val privateApps: List<AppItem> = emptyList(),
    val privateSpaceEntry: Boolean = false,
)
