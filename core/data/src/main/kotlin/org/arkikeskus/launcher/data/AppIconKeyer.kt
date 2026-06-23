package org.arkikeskus.launcher.data

import coil3.key.Keyer
import coil3.request.Options
import org.arkikeskus.launcher.model.AppItem

class AppIconKeyer : Keyer<AppItem> {
    override fun key(data: AppItem, options: Options): String = "appicon:${data.key}"
}
