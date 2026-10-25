package org.arkikeskus.launcher

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import org.arkikeskus.launcher.data.PendingWidgetIds
import org.arkikeskus.launcher.data.SharedPreferencesPendingIdStorage
import javax.inject.Inject

@HiltAndroidApp
class LauncherApplication : Application(), SingletonImageLoader.Factory {

    /** Hilt-provided ImageLoader with the app-icon fetcher/keyer (see DataModule). */
    @Inject
    lateinit var imageLoader: ImageLoader

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader

    @Inject
    lateinit var lockSounds: LockSounds

    override fun onCreate() {
        super.onCreate()
        // Before any activity, so the home screen's first sweep already knows the widget ids another
        // app's request held when a previous process died with the system's bind dialog open.
        PendingWidgetIds.shared.persistIn(SharedPreferencesPendingIdStorage(this))
        lockSounds.start()
    }
}
