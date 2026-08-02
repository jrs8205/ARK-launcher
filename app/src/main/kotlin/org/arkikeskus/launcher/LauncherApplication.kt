package org.arkikeskus.launcher

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.feature.updater.UpdateScheduler
import org.arkikeskus.launcher.feature.updater.isReleaseBuild
import javax.inject.Inject

@HiltAndroidApp
class LauncherApplication : Application(), SingletonImageLoader.Factory, Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        if (isReleaseBuild(this)) {
            // Schedule the periodic update check only when auto-update is enabled; cancel it otherwise.
            // (Reads the persisted setting off the main thread; the worker also double-checks the flag.)
            // runCatching: this is a root coroutine on every process start of the HOME app — an
            // uncaught DataStore IOException or WorkManager init error here would kill the process
            // again on every launch, a genuine HOME crash loop. Losing one scheduling pass is fine;
            // the next process start (or the settings toggle) reschedules.
            CoroutineScope(Dispatchers.Default).launch {
                runCatching {
                    if (settingsRepository.autoUpdateEnabledOnce()) {
                        UpdateScheduler.schedule(this@LauncherApplication)
                    } else {
                        UpdateScheduler.cancel(this@LauncherApplication)
                    }
                }
            }
        }
    }

    /** Hilt-provided ImageLoader with the app-icon fetcher/keyer (see DataModule). */
    @Inject
    lateinit var imageLoader: ImageLoader

    /** Reads the auto-update toggle at startup to decide whether to schedule the periodic check. */
    @Inject
    lateinit var settingsRepository: SettingsRepository

    /** Hilt-provided WorkerFactory so [@HiltWorker] workers receive injected dependencies. */
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /** Supplies the custom [Configuration] that wires Hilt's worker factory into WorkManager. */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader
}
