package org.arkikeskus.launcher.data

import android.os.Build

/** Where the apps of a profile go when the launcher lists them. */
internal enum class Listing {
    /** The shared list every screen reads: the own user's apps, a work profile's, a clone's. */
    General,

    /** The private space's own list: memory only, shown on the Private tab alone. */
    Private,

    /** Not listed at all: a profile the launcher cannot tell the kind of. */
    Nowhere,
}

/**
 * Where a profile's apps are listed, decided before anything about them is read or cached.
 *
 * The own user is always in the shared list. Below Android 15 nothing is typed and there is no private space:
 * every profile is in the shared list, as it always was. From Android 15 the platform types profiles, and only a
 * profile positively typed as something other than private joins the shared list; one typed private goes to its
 * own list; an untyped one, one the snapshot does not know ([kinds] by serial), or one whose [serial] cannot be
 * read is listed nowhere: it may be a private space the launcher was not told about, and private apps must never
 * show up as personal ones.
 */
internal fun listingOf(isOwnUser: Boolean, serial: Long?, kinds: Map<Long, ProfileKind>, sdkInt: Int): Listing = when {
    isOwnUser -> Listing.General
    sdkInt < Build.VERSION_CODES.VANILLA_ICE_CREAM -> Listing.General
    serial == null -> Listing.Nowhere
    else -> when (kinds[serial]) {
        ProfileKind.Own, ProfileKind.Managed, ProfileKind.Clone -> Listing.General
        ProfileKind.Private -> Listing.Private
        ProfileKind.Unknown, null -> Listing.Nowhere
    }
}
