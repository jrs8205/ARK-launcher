package org.arkikeskus.launcher

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.arkikeskus.launcher.data.CustomIconStore
import org.arkikeskus.launcher.data.PendingWidgetIds
import org.arkikeskus.launcher.data.SharedPreferencesPendingIdStorage
import org.arkikeskus.launcher.data.di.ApplicationScope
import javax.inject.Inject

@HiltAndroidApp
class LauncherApplication : Application(), SingletonImageLoader.Factory {

    /** Hilt-provided ImageLoader with the app-icon fetcher/keyer (see DataModule). */
    @Inject
    lateinit var imageLoader: ImageLoader

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader

    @Inject
    lateinit var lockSounds: LockSounds

    @Inject
    lateinit var customIcons: CustomIconStore

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onCreate() {
        val startedAt = System.currentTimeMillis()
        super.onCreate()
        // Before any activity, so the home screen's first sweep already knows the widget ids another
        // app's request held when a previous process died with the system's bind dialog open.
        PendingWidgetIds.shared.persistIn(SharedPreferencesPendingIdStorage(this))
        lockSounds.start()
        // Gallery pictures an earlier run left unused (see CustomIconStore.deleteUnusedPictures).
        scope.launch(Dispatchers.IO) { runCatching { customIcons.deleteUnusedPictures(savedBefore = startedAt) } }
    }
}
