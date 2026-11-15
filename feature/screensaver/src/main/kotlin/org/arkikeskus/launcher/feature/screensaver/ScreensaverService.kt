package org.arkikeskus.launcher.feature.screensaver

import android.content.pm.ActivityInfo
import android.service.dreams.DreamService
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dagger.hilt.android.AndroidEntryPoint
import org.arkikeskus.launcher.data.NotificationBadgeRepository
import org.arkikeskus.launcher.data.screensaver.DreamPreferences
import org.arkikeskus.launcher.data.screensaver.screensaverPreferences
import org.arkikeskus.launcher.feature.screensaver.dream.DreamHost
import org.arkikeskus.launcher.feature.screensaver.dream.DreamStyle
import org.arkikeskus.launcher.feature.screensaver.dream.LocalDreamWideGamut
import org.arkikeskus.launcher.feature.screensaver.dream.currentMinuteOfDay
import org.arkikeskus.launcher.feature.screensaver.dream.dreamWindowBrightness
import org.arkikeskus.launcher.feature.screensaver.dream.readDreamOptions
import org.arkikeskus.launcher.feature.screensaver.dream.setDreamBrightness
import org.arkikeskus.launcher.feature.screensaver.dream.shouldDimNow
import org.arkikeskus.launcher.feature.screensaver.dream.useWideColorGamut
import javax.inject.Inject

/**
 * The screensaver. This class is only the service: its lifecycle and its window (orientation,
 * dimming and colour mode). What it draws lives in the dream package. Android's screensaver setting
 * stores this class's name, so the name and the package never change.
 */
@AndroidEntryPoint
class ScreensaverService : DreamService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    /** The listener's list of what is in the shade; empty until the listener has connected. */
    @Inject
    lateinit var notifications: NotificationBadgeRepository

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val lifecycleDriver = DreamLifecycleDriver(lifecycleRegistry)
    private val store = ViewModelStore()
    private val controller = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle = lifecycleRegistry
    override val viewModelStore: ViewModelStore = store
    override val savedStateRegistry: SavedStateRegistry = controller.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        controller.performRestore(null)
        lifecycleDriver.onCreate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        isInteractive = true
        isFullscreen = true

        // Optionally lock the screensaver to portrait (user setting). On Android 14+ the dream
        // is hosted by a framework DreamActivity, and DreamService exposes no orientation API —
        // but the dream window's context IS that host activity, so we set the orientation through
        // the real Activity.setRequestedOrientation API. The Compose content then picks the
        // portrait layout from the reported configuration.
        val dreamPrefs = screensaverPreferences()
        val forcePortrait = dreamPrefs.getBoolean(DreamPreferences.KEY_FORCE_PORTRAIT, false)
        (window?.context as? android.app.Activity)?.requestedOrientation = if (forcePortrait) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        // Optional dim mode (user setting): manual dim, or an automatic night window. Must be set
        // no later than onDreamingStarted(), so onAttachedToWindow() is a valid place. The clock
        // loop re-evaluates this once a minute so a running screensaver dims/brightens live when
        // the night window starts or ends.
        val dimNow = shouldDimNow(dreamPrefs, currentMinuteOfDay())
        setScreenBright(!dimNow)

        // The style and the other options are read once per dream, here; they are changed in
        // Settings, never while the dream is up.
        val options = readDreamOptions(dreamPrefs)

        // The Edge style's line is as saturated as the screen allows: on a wide-gamut screen
        // the window is switched to wide colour and the style draws its Display P3 colours.
        val wideGamut = options.style == DreamStyle.EDGE && resources.configuration.isScreenWideColorGamut
        if (wideGamut) {
            window?.useWideColorGamut()
        }
        // The screensaver's own brightness (user setting), though not while it is dimmed.
        // The clock loop asks again once a minute, like the dimming above.
        window?.setDreamBrightness(dreamWindowBrightness(options, dimNow))

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@ScreensaverService)
            setViewTreeViewModelStoreOwner(this@ScreensaverService)
            setViewTreeSavedStateRegistryOwner(this@ScreensaverService)

            setContent {
                ProvideBoundedDensity {
                    CompositionLocalProvider(LocalDreamWideGamut provides wideGamut) {
                        DreamHost(
                            service = this@ScreensaverService,
                            prefs = dreamPrefs,
                            options = options,
                            notifications = notifications.active,
                            onChargeState = {},
                        )
                    }
                }
            }
        }

        setContentView(composeView)
        lifecycleDriver.onAttachedToWindow()
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        isDreaming = true
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        isDreaming = false
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // May arrive after onDestroy() on Android 14+; the driver skips it then.
        lifecycleDriver.onDetachedFromWindow()
    }

    override fun onDestroy() {
        super.onDestroy()
        isDreaming = false
        lifecycleDriver.onDestroy()
        store.clear()
    }

    companion object {
        /** Whether this screensaver is up, for the start on charge: same process. */
        @Volatile
        var isDreaming = false
            private set
    }
}
