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

/** Coil model for drawing a [CustomIcon] on its own: a folder's picked icon, the icon picker's grid. */
data class CustomIconImage(val icon: CustomIcon)
