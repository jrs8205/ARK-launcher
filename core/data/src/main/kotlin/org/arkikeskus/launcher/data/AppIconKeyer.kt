package org.arkikeskus.launcher.data

import coil3.key.Keyer
import coil3.request.Options
import org.arkikeskus.launcher.model.IconRequest

class AppIconKeyer : Keyer<IconRequest> {
    // The themed/dark flags AND the selected icon pack are in the key so each variant caches separately
    // and a settings/theme change re-fetches instead of serving a stale bitmap. Omitting the icon pack
    // was a bug: selecting a pack changed the model but not the cache key, so the old icon was served.
    override fun key(data: IconRequest, options: Options): String =
        "appicon:${data.app.key}:t=${data.themed}:d=${data.dark}:p=${data.iconPack}"
}
