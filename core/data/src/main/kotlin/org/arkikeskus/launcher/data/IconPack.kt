package org.arkikeskus.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Xml
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import org.xmlpull.v1.XmlPullParser
import javax.inject.Inject
import javax.inject.Singleton

/** An installed icon pack, for the settings picker. */
data class IconPackInfo(val packageName: String, val label: String)

/**
 * A parsed third-party icon pack (the de-facto ADW/Nova `appfilter.xml` format). Maps an app component
 * to one of the pack's drawables, and can mask an unmapped app's normal icon into the pack's style
 * (iconback + iconmask + iconupon + scale) so every icon looks consistent — the Nova/Lawnchair
 * convention. Adapted from AOSP-adjacent icon-pack handling; our repo is Apache-2.0.
 */
class IconPack(
    private val packageName: String,
    private val res: Resources,
    private val componentMap: Map<String, String>,
    private val iconBack: Bitmap?,
    private val iconMask: Bitmap?,
    private val iconUpon: Bitmap?,
    private val scale: Float,
) {
    /** True if the pack defines any masking layer (so unmapped icons can be normalised to its style). */
    val hasMask: Boolean get() = iconBack != null || iconMask != null || iconUpon != null

    /** The pack's drawable for [component], or null if the pack doesn't map it. */
    fun getIcon(component: ComponentName): Drawable? {
        val name = componentMap["${component.packageName}/${component.className}"] ?: return null
        val id = res.getIdentifier(name, "drawable", packageName).takeIf { it != 0 } ?: return null
        return runCatching { ResourcesCompat.getDrawable(res, id, null) }.getOrNull()
    }

    /**
     * Composites [base] (an unmapped app's normal icon) into the pack's style at [size] px: the icon is
     * scaled by [scale], clipped by iconmask (its opaque pixels erase the icon), drawn over iconback and
     * under iconupon. If the pack has no mask layers this just returns the (scaled) icon.
     */
    fun maskIcon(base: Drawable, size: Int): Drawable {
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val full = Rect(0, 0, size, size)

        // 1. The app icon, scaled toward the centre by [scale].
        val inset = ((1f - scale).coerceIn(0f, 0.9f) * size / 2f).toInt()
        canvas.drawBitmap(base.toBitmap(width = size, height = size), null, Rect(inset, inset, size - inset, size - inset), paint)
        // 2. iconmask erases the icon where the mask is opaque (e.g. the corners outside a squircle).
        iconMask?.let {
            canvas.drawBitmap(it, null, full, Paint(paint).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        }
        // 3. iconback is drawn BEHIND the masked icon.
        iconBack?.let {
            canvas.drawBitmap(it, null, full, Paint(paint).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OVER) })
        }
        // 4. iconupon (gloss/overlay) on top.
        iconUpon?.let { canvas.drawBitmap(it, null, full, paint) }
        return BitmapDrawable(res, result)
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
        var back: String? = null
        var mask: String? = null
        var upon: String? = null
        var scale = 1f
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "item" -> {
                            val comp = componentKey(parser.getAttributeValue(null, "component"))
                            val drawable = parser.getAttributeValue(null, "drawable")
                            if (comp != null && !drawable.isNullOrEmpty() && comp !in map) map[comp] = drawable
                        }
                        // Packs may list several iconbacks; the first is fine for a consistent look.
                        "iconback" -> if (back == null) back = parser.getAttributeValue(null, "img")
                            ?: parser.getAttributeValue(null, "img1")
                        "iconmask" -> if (mask == null) mask = parser.getAttributeValue(null, "img")
                            ?: parser.getAttributeValue(null, "img1")
                        "iconupon" -> if (upon == null) upon = parser.getAttributeValue(null, "img")
                            ?: parser.getAttributeValue(null, "img1")
                        "scale" -> scale = parser.getAttributeValue(null, "factor")?.toFloatOrNull() ?: scale
                    }
                }
                event = parser.next()
            }
        } finally {
            runCatching { closeable.close() }
        }
        return IconPack(
            packageName = pkg,
            res = res,
            componentMap = map,
            iconBack = loadBitmap(res, pkg, back),
            iconMask = loadBitmap(res, pkg, mask),
            iconUpon = loadBitmap(res, pkg, upon),
            scale = scale.coerceIn(0.3f, 1.2f),
        )
    }

    /** "ComponentInfo{pkg/cls}" → "pkg/cls" (the map key); null for non-component entries. */
    private fun componentKey(raw: String?): String? {
        if (raw == null) return null
        val inner = raw.substringAfter('{', "").substringBefore('}', "")
        return inner.takeIf { it.isNotEmpty() && '/' in inner }
    }

    private fun loadBitmap(res: Resources, pkg: String, name: String?): Bitmap? {
        if (name.isNullOrEmpty()) return null
        val id = res.getIdentifier(name, "drawable", pkg).takeIf { it != 0 } ?: return null
        return runCatching { ResourcesCompat.getDrawable(res, id, null)?.toBitmap() }.getOrNull()
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
