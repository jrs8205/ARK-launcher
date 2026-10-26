package org.arkikeskus.launcher.model

/**
 * An icon the user picked for one app or folder in place of its own, as in Lawnchair and Nova
 * Launcher: a drawable of an installed icon pack, or a gallery image the launcher saved in its own
 * files. Stored as text ([encode] / [decode]).
 */
sealed interface CustomIcon {
    /** The drawable named [drawable] in the icon pack [pack] (its package name). */
    data class FromPack(val pack: String, val drawable: String) : CustomIcon

    /** The image file [name] in the launcher's custom-icon folder. */
    data class FromFile(val name: String) : CustomIcon

    fun encode(): String = when (this) {
        is FromPack -> "$PACK$pack:$drawable"
        is FromFile -> "$FILE$name"
    }

    companion object {
        private const val PACK = "pack:"
        private const val FILE = "file:"

        /** The icon [raw] names, or null when it is no valid reference — and a file name may never
         *  point outside the launcher's custom-icon folder. */
        fun decode(raw: String): CustomIcon? = when {
            raw.startsWith(PACK) -> raw.removePrefix(PACK).split(':', limit = 2)
                .takeIf { it.size == 2 && it.all(String::isNotBlank) }
                ?.let { (pack, drawable) -> FromPack(pack, drawable) }
            raw.startsWith(FILE) -> raw.removePrefix(FILE)
                .takeIf { it.isNotBlank() && '/' !in it && '\\' !in it && !it.startsWith("..") }
                ?.let(::FromFile)
            else -> null
        }
    }
}

/** Coil model for drawing a [CustomIcon] on its own: a folder's picked icon, the icon picker's grid.
 *  [epoch] is the [IconEpochs] token of the icon's pack, as [IconRequest.epoch] is of an app's. */
data class CustomIconImage(val icon: CustomIcon, val epoch: Int = 0)

/** An installed icon pack the picker offers. */
data class IconPackChoice(val packageName: String, val label: String)

/**
 * What the icon picker offers, as in Nova Launcher and Lawnchair: the installed icon packs, every
 * icon of one, the pack's own match for an app, and a gallery image turned into an icon. Blocking
 * calls (packs are parsed, images decoded): call them off the main thread.
 */
interface IconChoices {
    fun packs(): List<IconPackChoice>
    fun icons(pack: String): List<String>
    fun suggestion(pack: String, app: AppItem): String?
    /** Saves the picture at [uri] as an icon, or null when it is no readable picture. */
    fun importImage(uri: android.net.Uri): CustomIcon?
    /** Deletes an imported image that ended up unused (the picker was left without saving). */
    fun discard(icon: CustomIcon)
}
