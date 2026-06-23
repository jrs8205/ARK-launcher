package org.arkikeskus.launcher.data

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.arkikeskus.launcher.model.AppItem

/** Coil fetcher that resolves an [AppItem]'s launcher icon via [LauncherAppsSource]. */
class AppIconFetcher(
    private val data: AppItem,
    private val source: LauncherAppsSource,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val drawable = withContext(Dispatchers.IO) { source.loadIcon(data) } ?: return null
        return ImageFetchResult(
            image = drawable.asImage(),
            isSampled = false,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val source: LauncherAppsSource) : Fetcher.Factory<AppItem> {
        override fun create(data: AppItem, options: Options, imageLoader: ImageLoader): Fetcher =
            AppIconFetcher(data, source)
    }
}
