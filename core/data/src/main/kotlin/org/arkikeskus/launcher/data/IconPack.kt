package org.arkikeskus.launcher.data

import java.util.concurrent.atomic.AtomicInteger
import android.annotation.SuppressLint
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
        val name = drawableName(component) ?: return null
        val id = res.getIdentifier(name, "drawable", packageName).takeIf { it != 0 } ?: return null
        return runCatching { ResourcesCompat.getDrawable(res, id, null) }.getOrNull()
    }

    /** The name of the drawable the pack maps [component] to — its own match for the app. */
    fun drawableName(component: ComponentName): String? = componentMap["${component.packageName}/${component.className}"]

    /** Every drawable the appfilter maps an app to, once each — the pack's icons when it lists none. */
    fun mappedDrawables(): List<String> = componentMap.values.distinct()
}

/**
 * The drawables an icon pack lists for icon pickers in its `drawable.xml` (`<item drawable="…"/>`,
 * grouped under `<category>`s), once each and in the pack's order.
 */
internal fun parseDrawableList(parser: XmlPullParser): List<String> {
    val names = LinkedHashSet<String>()
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG && parser.name == "item") {
            parser.getAttributeValue(null, "drawable")?.takeIf { it.isNotBlank() }?.let(names::add)
        }
        event = parser.next()
    }
    return names.toList()
}

/**
 * Discovers installed icon packs and loads/parses them on demand (cached). Injected into
 * [LauncherAppsSource] so icon resolution can consult the user's selected pack, and into the settings
 * feature for the picker list.
 */
@Singleton
class IconPackRepository internal constructor(
    private val context: Context,
    /** Reads and parses one pack's appfilter: slow for a big pack, so it runs outside the monitor. */
    private val loader: (String) -> IconPack?,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(context, AppFilterLoader(context)::load)

    // package -> parsed pack (or null when it has no usable appfilter); LinkedHashMap for a stable order.
    private val cache = HashMap<String, IconPack?>()
    // Bumped by every invalidation: a parse that overlapped one is stale and is not cached.
    private val generation = AtomicInteger()
    // package -> its resources, for single drawables picked from it (see [drawable]).
    private val resources = HashMap<String, Resources?>()

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

    /** Drops [pkg]'s cached parse, resources and icon list (the pack was updated/uninstalled) so the
     *  next use re-reads them. Returns true if any of them was cached — incl. a cached negative, and a
     *  pack read only for a picked icon — so the caller knows any already rasterized icons may be stale. */
    @Synchronized
    fun invalidate(pkg: String): Boolean {
        val wasCached = cache.containsKey(pkg) || resources.containsKey(pkg) || names.containsKey(pkg)
        cache.remove(pkg)
        resources.remove(pkg)
        names.remove(pkg)
        generation.incrementAndGet()
        return wasCached
    }

    // package -> every icon it offers in the picker (see [iconNames]).
    private val names = HashMap<String, List<String>>()

    /** Every icon the pack [pkg] offers for picking: its `drawable.xml` list (res/xml or assets),
     *  else the icons its appfilter maps; only names the pack really has. Cached. */
    // An icon pack's icons exist only as named resources of another app: looking them up by name is
    // the only way to reach them (as in every launcher that reads icon packs).
    @SuppressLint("DiscouragedApi")
    fun iconNames(pkg: String): List<String> {
        synchronized(this) { names[pkg]?.let { return it } }
        val res = packResources(pkg) ?: return emptyList()
        val listed = runCatching { readDrawableList(pkg, res) }.getOrNull().orEmpty()
        val all = listed.ifEmpty { get(pkg)?.mappedDrawables().orEmpty() }
            .filter { res.getIdentifier(it, "drawable", pkg) != 0 }
        synchronized(this) { names[pkg] = all }
        return all
    }

    /** The pack [pkg]'s own match for [component] (its appfilter), to show first in the picker. */
    fun suggestion(pkg: String, component: ComponentName): String? = get(pkg)?.drawableName(component)

    @SuppressLint("DiscouragedApi")
    private fun readDrawableList(pkg: String, res: Resources): List<String> {
        val xmlId = res.getIdentifier("drawable", "xml", pkg)
        if (xmlId != 0) return res.getXml(xmlId).use(::parseDrawableList)
        return context.createPackageContext(pkg, 0).assets.open("drawable.xml").use { stream ->
            parseDrawableList(Xml.newPullParser().apply { setInput(stream, null) })
        }
    }

    /** The drawable [name] of the installed pack [pkg] — an icon picked from it for one app or
     *  folder — or null when the pack or the drawable is gone. */
    @SuppressLint("DiscouragedApi")
    fun drawable(pkg: String, name: String): Drawable? = runCatching {
        val res = packResources(pkg) ?: return null
        val id = res.getIdentifier(name, "drawable", pkg).takeIf { it != 0 } ?: return null
        ResourcesCompat.getDrawable(res, id, null)
    }.getOrNull()

    @Synchronized
    private fun packResources(pkg: String): Resources? = resources.getOrPut(pkg) {
        runCatching { context.packageManager.getResourcesForApplication(pkg) }.getOrNull()
    }

    /** Loads + parses [pkg] (cached, including a cached "no pack" result). The parse runs OUTSIDE
     *  the monitor: a big pack takes seconds, and a package event on the main thread (which
     *  invalidates) must not wait for it. A result that overlapped an invalidation is not kept. */
    fun get(pkg: String): IconPack? {
        if (pkg.isBlank()) return null
        synchronized(this) { if (cache.containsKey(pkg)) return cache[pkg] }
        val before = generation.get()
        val pack = runCatching { loader(pkg) }
            .onFailure { android.util.Log.w("IconPack", "load($pkg) failed: $it") }
            .getOrNull()
        synchronized(this) {
            if (cache.containsKey(pkg)) return cache[pkg]
            if (generation.get() == before) cache[pkg] = pack
        }
        return pack
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

/** Reads a pack's `appfilter` (res/xml, else assets) into an [IconPack]; null when it has none. */
private class AppFilterLoader(private val context: Context) {
    fun load(pkg: String): IconPack? {
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
}
