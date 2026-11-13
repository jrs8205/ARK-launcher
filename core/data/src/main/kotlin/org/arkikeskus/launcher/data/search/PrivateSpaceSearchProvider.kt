package org.arkikeskus.launcher.data.search

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.arkikeskus.launcher.data.PrivateSpace
import org.arkikeskus.launcher.data.PrivateSpaceRepository
import org.arkikeskus.launcher.data.R
import org.arkikeskus.launcher.model.SearchResult
import javax.inject.Inject

/**
 * The private space in the search. Its apps are matched by label only while the space is unlocked (the
 * repository's list is empty otherwise) and each match is flagged private. The space itself is a result too,
 * [SearchResult.PrivateSpaceEntry]: the way in, and the only one while the space is locked with its entry
 * hidden. It is matched by the space's name in the phone's language or in English ([privateSpaceEntryMatches]).
 * Nothing is searched, and nothing found, on a phone without a private space.
 */
class PrivateSpaceSearchProvider internal constructor(
    private val privateSpace: PrivateSpaceRepository,
    /** The private space's names in the phone's language, as the string resource has them. */
    private val localizedNames: () -> String,
) : SearchProvider {

    @Inject constructor(@ApplicationContext context: Context, privateSpace: PrivateSpaceRepository) :
        this(privateSpace, { context.getString(R.string.private_space_search_phrases) })

    override suspend fun isEnabled(): Boolean = privateSpace.state.value != null

    override suspend fun query(query: String): List<SearchResult> {
        val space = privateSpace.state.value ?: return emptyList()
        val q = query.trim()
        val apps = privateSpace.apps.value
            .filter { it.label.contains(q, ignoreCase = true) }
            .map { SearchResult.App(it, privateProfile = true) }
        // A locked space whose entry is to stay out of sight is not given away by a casual search.
        return if (privateSpaceEntryOffered(q, space, localizedNames())) apps + SearchResult.PrivateSpaceEntry else apps
    }
}

private const val ENGLISH_NAME = "private space"

/** The private space's names: the phone's language's ([localized], several separated by a bar) and the English one. */
internal fun privateSpaceNames(localized: String): List<String> =
    (localized.split('|') + ENGLISH_NAME).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/**
 * Whether the private space's own row may be offered for [query] with the space as it is: never without a space;
 * for the first letters of one of its names; only for a whole name while it is locked with its entry hidden.
 * [localizedNames] is the text of the string resource (the names in the phone's language, bar-separated).
 */
fun privateSpaceEntryOffered(query: String, space: PrivateSpace?, localizedNames: String): Boolean {
    if (space == null) return false
    return privateSpaceEntryMatches(query, privateSpaceNames(localizedNames), wholeNameOnly = space.locked && space.hiddenWhenLocked)
}

/** [privateSpaceEntryOffered] with the names read in the language [context] shows. */
fun privateSpaceEntryOffered(context: Context, query: String, space: PrivateSpace?): Boolean =
    privateSpaceEntryOffered(query, space, context.getString(R.string.private_space_search_phrases))

/** The fewest letters that offer the private space's row: fewer are the beginning of any word. */
private const val MIN_NAME_PREFIX = 3

/**
 * Whether [query] asks for the private space: it is the beginning (at least [MIN_NAME_PREFIX] letters) of one
 * of its [names], case aside; with [wholeNameOnly] it must be a whole name.
 */
internal fun privateSpaceEntryMatches(query: String, names: List<String>, wholeNameOnly: Boolean): Boolean {
    val q = query.trim()
    return if (wholeNameOnly) {
        names.any { it.equals(q, ignoreCase = true) }
    } else {
        q.length >= MIN_NAME_PREFIX && names.any { it.startsWith(q, ignoreCase = true) }
    }
}
