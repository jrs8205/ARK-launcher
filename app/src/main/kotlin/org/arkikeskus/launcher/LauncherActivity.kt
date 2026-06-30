package org.arkikeskus.launcher

import android.appwidget.AppWidgetHost
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableSharedFlow
import org.arkikeskus.launcher.designsystem.theme.LauncherTheme
import org.arkikeskus.launcher.feature.home.APPWIDGET_HOST_ID
import org.arkikeskus.launcher.feature.home.LocalAppWidgetHost
import org.arkikeskus.launcher.feature.home.LocalWidgetConfigLauncher
import org.arkikeskus.launcher.ui.LauncherShell

@AndroidEntryPoint
class LauncherActivity : ComponentActivity() {

    /** Emits when HOME is pressed while we are already the foreground home app (onNewIntent). */
    private val homeSignals = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    // Use the Activity context (not applicationContext) for the host — Launcher3 does the same; a
    // collection widget's RemoteViewsAdapter and the host's listener callbacks register against this.
    private val appWidgetHost by lazy { AppWidgetHost(this, APPWIDGET_HOST_ID) }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LauncherTheme {
                CompositionLocalProvider(
                    LocalAppWidgetHost provides appWidgetHost,
                    LocalWidgetConfigLauncher provides ::startWidgetConfig,
                ) {
                    LauncherShell(
                        homeSignals = homeSignals,
                        onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    )
                }
            }
        }
    }

    /** Pending callback for the in-flight widget configuration activity (see [startWidgetConfig]). */
    private var pendingConfigCallback: ((Boolean) -> Unit)? = null

    /**
     * Launches [appWidgetId]'s configuration activity through the system so the framework marks the
     * widget configured (a raw ACTION_APPWIDGET_CONFIGURE Intent does not). The result arrives in
     * [onActivityResult]. Used by the home screen's add-widget flow via [LocalWidgetConfigLauncher].
     */
    private fun startWidgetConfig(appWidgetId: Int, onResult: (Boolean) -> Unit) {
        pendingConfigCallback = onResult
        val launched = runCatching {
            appWidgetHost.startAppWidgetConfigureActivityForResult(this, appWidgetId, 0, WIDGET_CONFIG_REQUEST, null)
        }.isSuccess
        if (!launched) {
            pendingConfigCallback = null
            onResult(false)
        }
    }

    @Deprecated("Required for AppWidgetHost.startAppWidgetConfigureActivityForResult (legacy result API)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == WIDGET_CONFIG_REQUEST) {
            val cb = pendingConfigCallback
            pendingConfigCallback = null
            cb?.invoke(resultCode == android.app.Activity.RESULT_OK)
        }
    }

    override fun onStart() {
        super.onStart()
        // A launcher is the device HOME — a host hiccup must never crash it.
        runCatching { appWidgetHost.startListening() }
    }

    override fun onStop() {
        super.onStop()
        runCatching { appWidgetHost.stopListening() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        homeSignals.tryEmit(Unit)
    }

    private companion object {
        /** Request code for the system-routed widget configuration activity. */
        const val WIDGET_CONFIG_REQUEST = 0x4357
    }
}
