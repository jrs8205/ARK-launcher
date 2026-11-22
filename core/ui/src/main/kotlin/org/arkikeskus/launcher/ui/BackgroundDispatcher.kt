package org.arkikeskus.launcher.ui

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Where a composable does what takes time before it can draw (an app's shortcuts, an icon pack's icons, a
 * picked picture): off the main thread, on [Dispatchers.IO].
 *
 * A local so that a test can put its own dispatcher here. With the real one, what was read comes back at a time
 * of its own, and under a test rule from the thread it was read on: a test that looked right after the screen
 * was idle found the rows not there yet, or the view updated from the wrong thread, when the machine was busy.
 */
val LocalBackgroundDispatcher = staticCompositionLocalOf<CoroutineDispatcher> { Dispatchers.IO }
