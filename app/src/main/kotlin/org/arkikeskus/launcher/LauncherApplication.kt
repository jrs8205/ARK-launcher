package org.arkikeskus.launcher

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class LauncherApplication : Application(), SingletonImageLoader.Factory, Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        if (org.arkikeskus.launcher.feature.updater.isReleaseBuild(this)) {
            org.arkikeskus.launcher.feature.updater.UpdateScheduler.schedule(this)
        }
    }

    /** Hilt-provided ImageLoader with the app-icon fetcher/keyer (see DataModule). */
    @Inject
    lateinit var imageLoader: ImageLoader

    /** Hilt-provided WorkerFactory so [@HiltWorker] workers receive injected dependencies. */
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /** Supplies the custom [Configuration] that wires Hilt's worker factory into WorkManager. */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader
}
