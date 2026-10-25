package org.arkikeskus.launcher

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.di.ApplicationScope
import org.arkikeskus.launcher.launcher.system.LockSoundChoice
import org.arkikeskus.launcher.launcher.system.LockSoundMonitor
import org.arkikeskus.launcher.launcher.system.ShortSoundPlayer
import javax.inject.Inject
import javax.inject.Singleton

/** The lock and unlock sounds the user picked (Settings → Sounds), for as long as the launcher's
 *  process runs — it is the phone's HOME, so that is all the time the phone is in use. */
@Singleton
class LockSounds @Inject constructor(
    @ApplicationContext private val context: Context,
    settings: SettingsRepository,
    @ApplicationScope scope: CoroutineScope,
) {
    private val choice = settings.settings
        .map { LockSoundChoice(it.lockSoundsEnabled, it.lockSoundUri, it.unlockSoundUri) }
        .stateIn(scope, SharingStarted.Eagerly, LockSoundChoice())

    private val monitor = LockSoundMonitor(context, { choice.value }, ShortSoundPlayer(context))

    fun start() = monitor.start()
}
