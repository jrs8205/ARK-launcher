package org.arkikeskus.launcher.feature.settings

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.core.app.ActivityOptionsCompat

/**
 * A request to the system (a picker) registered under a name of its own, [name], and not under one the page
 * keeps for it.
 *
 * `rememberLauncherForActivityResult` registers under a random name, which it keeps as saved state of the place
 * it is called at. A settings page starts anew with another home profile (its key), and saved state comes back
 * after a recreation only to the same place under the same key. After a profile switch and a recreation, both
 * under the open picker, the page registered under a new name, and the picker's answer found nobody: the sound
 * the user picked was not set.
 *
 * A name that is the same every time has no such state to lose. Whatever page is composed when the answer
 * comes gets it, and so does one composed after it (the registry holds an answer until its name is registered).
 *
 * So the name has to say everything the answer needs, as nothing kept beside the request can be counted on to
 * come back with it; and a name is registered by one caller at a time. It is for a request that is the same
 * for every home profile: a page's request that is of ONE profile must not outlive the page so.
 */
@Composable
internal fun <I, O> rememberKeptLauncher(
    name: String,
    contract: ActivityResultContract<I, O>,
    onResult: (O) -> Unit,
): ActivityResultLauncher<I> {
    val currentOnResult by rememberUpdatedState(onResult)
    val registry = checkNotNull(LocalActivityResultRegistryOwner.current) {
        "No ActivityResultRegistryOwner was provided via LocalActivityResultRegistryOwner"
    }.activityResultRegistry
    val launcher = remember(name) { KeptLauncher(contract) }
    DisposableEffect(registry, launcher) {
        launcher.registered = registry.register(KEPT_REQUEST_PREFIX + name, contract) { currentOnResult(it) }
        onDispose {
            launcher.registered?.unregister()
            launcher.registered = null
        }
    }
    return launcher
}

/** What the kept requests' names begin with in the registry: apart from the names anything else registers. */
private const val KEPT_REQUEST_PREFIX = "settings-request:"

/** The launcher [rememberKeptLauncher] hands out: it is there from the first composition, and launches through
 *  the registration once that is made (after the composition is applied, before anything can be tapped). */
private class KeptLauncher<I>(private val contract: ActivityResultContract<I, *>) : ActivityResultLauncher<I>() {
    var registered: ActivityResultLauncher<I>? = null

    override fun getContract(): ActivityResultContract<I, *> = contract

    override fun launch(input: I, options: ActivityOptionsCompat?) {
        checkNotNull(registered) { "Launcher has not been registered yet" }.launch(input, options)
    }

    override fun unregister(): Unit =
        throw UnsupportedOperationException("Registered and unregistered by the composition it is remembered in")
}
