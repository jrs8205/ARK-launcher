package org.arkikeskus.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.Xml
import androidx.core.content.res.ResourcesCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.xmlpull.v1.XmlPullParser
import javax.inject.Inject
import javax.inject.Singleton

/** An installed icon pack, for the settings picker. */
data class IconPackInfo(val packageName: String, val label: String)

/**
 * A parsed third-party icon pack (the de-facto ADW/Nova `appfilter.xml` format). Maps an app component
 * to one of the pack's drawables. Unmapped apps keep their normal icon (modern launchers such as
 * Lawnchair no longer mask unmapped icons into the pack style, and most current packs ship no mask).
 */
class IconPack(
    private val packageName: String,
    private val res: Resources,
    private val componentMap: Map<String, String>,
) {
    /** The pack's drawable for [component], or null if the pack doesn't map it. */
    fun getIcon(component: ComponentName): Drawable? {
        val name = componentMap["${component.packageName}/${component.className}"] ?: return null
        val id = res.getIdentifier(name, "drawable", packageName).takeIf { it != 0 } ?: return null
        return runCatching { ResourcesCompat.getDrawable(res, id, null) }.getOrNull()
    }
}

/**
 * Discovers installed icon packs and loads/parses them on demand (cached). Injected into
 * [LauncherAppsSource] so icon resolution can consult the user's selected pack, and into the settings
 * feature for the picker list.
 */
@Singleton
class IconPackRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // package -> parsed pack (or null when it has no usable appfilter); LinkedHashMap for a stable order.
    private val cache = HashMap<String, IconPack?>()

    /** Apps that declare one of the de-facto icon-pack theme intents, for the settings picker. Packs
     *  declare these either as an ACTION (Nova/ADW/Go) or as a category on a MAIN activity (Apex/Tesla),
     *  so we query both styles. */
    fun installedPacks(): List<IconPackInfo> {
        val pm = context.packageManager
        val packages = LinkedHashSet<String>()
        val intents = THEME_ACTIONS.map { Intent(it) } +
            THEME_CATEGORIES.map { Intent(Intent.ACTION_MAIN).addCategory(it) }
        for (intent in intents) {
            val list = runCatching { pm.queryIntentActivities(intent, 0) }.getOrElse { emptyList() }
            list.forEach { packages.add(it.activityInfo.packageName) }
        }
        return packages.mapNotNull { pkg ->
            runCatching {
                IconPackInfo(pkg, pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString())
            }.getOrNull()
        }.sortedBy { it.label.lowercase() }
    }

    /** Drops [pkg]'s cached parse (the pack was updated/uninstalled) so the next [get] re-reads it.
     *  Returns true if [pkg] was cached — incl. a cached negative — so the caller knows any already
     *  rasterized icons may be stale. */
    @Synchronized
    fun invalidate(pkg: String): Boolean {
        if (!cache.containsKey(pkg)) return false
        cache.remove(pkg)
        return true
    }

    /** Loads + parses [pkg] (cached, including a cached "no pack" result). */
    @Synchronized
    fun get(pkg: String): IconPack? {
        if (pkg.isBlank()) return null
        if (cache.containsKey(pkg)) return cache[pkg]
        val pack = runCatching { load(pkg) }
            .onFailure { android.util.Log.w("IconPack", "load($pkg) failed: $it") }
            .getOrNull()
        cache[pkg] = pack
        return pack
    }

    private fun load(pkg: String): IconPack? {
        val res = context.packageManager.getResourcesForApplication(pkg)
        // Prefer res/xml/appfilter, else assets/appfilter.xml (icon packs use one or the other).
        val xmlId = res.getIdentifier("appfilter", "xml", pkg)
        val parser: XmlPullParser
        val closeable: AutoCloseable
        if (xmlId != 0) {
            val xp = res.getXml(xmlId)
            parser = xp; closeable = AutoCloseable { xp.close() }
        } else {
            val stream = context.createPackageContext(pkg, 0).assets.open("appfilter.xml")
            parser = Xml.newPullParser().apply { setInput(stream, null) }
            closeable = stream
        }

        val map = HashMap<String, String>()
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "item") {
                    val comp = componentKey(parser.getAttributeValue(null, "component"))
                    val drawable = parser.getAttributeValue(null, "drawable")
                    if (comp != null && !drawable.isNullOrEmpty() && comp !in map) map[comp] = drawable
                }
                event = parser.next()
            }
        } finally {
            runCatching { closeable.close() }
        }
        return IconPack(packageName = pkg, res = res, componentMap = map)
    }

    /** "ComponentInfo{pkg/cls}" → "pkg/cls" (the map key); null for non-component entries. */
    private fun componentKey(raw: String?): String? {
        if (raw == null) return null
        val inner = raw.substringAfter('{', "").substringBefore('}', "")
        return inner.takeIf { it.isNotEmpty() && '/' in inner }
    }

    private companion object {
        /** Theme identifiers packs declare as an intent ACTION (Nova/ADW/Go/Atom/Lawnchair). */
        val THEME_ACTIONS = listOf(
            "org.adw.launcher.THEMES",
            "com.novalauncher.THEME",
            "com.gau.go.launcherex.theme",
            "com.dlto.atom.launcher.THEME",
            "ch.deletescape.lawnchair.ICONPACK",
            "app.lawnchair.icons.THEMED_ICON",
        )

        /** Theme identifiers packs declare as a CATEGORY on a MAIN activity (Apex/Tesla/Fede). */
        val THEME_CATEGORIES = listOf(
            "com.anddoes.launcher.THEME",
            "com.teslacoilsw.launcher.THEME",
            "com.fede.launcher.THEME_ICONPACK",
        )
    }
}
