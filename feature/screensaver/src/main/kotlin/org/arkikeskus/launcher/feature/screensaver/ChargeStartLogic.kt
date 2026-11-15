package org.arkikeskus.launcher.feature.screensaver

/** What to do when charging begins, so the screensaver comes up without waiting for a wake. */
internal enum class ChargeStartAction { NONE, WAKE_SCREEN }

/** Whether Android would show our screensaver on a charging phone, as far as can be told. */
internal enum class ChargeDream { OURS, NOT_OURS, UNKNOWN }

/**
 * Android starts a screensaver only when the screen times out while charging. With the
 * Always On Display on, plugging in does not wake the phone, so there is no timeout and
 * the screensaver waits until someone wakes the screen. Waking the screen lets the lock
 * screen time out into the screensaver a few seconds later. A screen that is already on is
 * left alone: unlocked in use, a call or navigation shown over the lock screen, or the lock
 * screen itself, which now times out into the screensaver by itself.
 *
 * An uncertain [selection] gets the wake too: Android's own timeout then applies Android's
 * own rules, and waking a screen for nothing costs only a few lit seconds.
 */
internal fun chargeStartAction(
    enabled: Boolean,
    screenOn: Boolean,
    dreamShowing: Boolean,
    selection: ChargeDream,
): ChargeStartAction = when {
    !enabled || screenOn || dreamShowing || selection == ChargeDream.NOT_OURS -> ChargeStartAction.NONE
    else -> ChargeStartAction.WAKE_SCREEN
}

/**
 * Reads Android's screensaver settings for a charging phone. The arguments are the raw
 * values of `screensaver_enabled`, `screensaver_activate_on_sleep` and
 * `screensaver_components`; null or the text "null" means unset. Android shows the first
 * screensaver of the list (DreamManagerService.chooseDreamForUser), and no choice at all
 * means the system's own. Unset switches fall back to defaults that differ by device (AOSP
 * leaves "while charging" off, a Pixel turns it on), so they make the answer uncertain.
 */
internal fun chargeDreamSelection(
    enabled: String?,
    activateOnSleep: String?,
    components: String?,
    ours: String,
): ChargeDream {
    val switchedOn = enabled.setting()
    val onCharge = activateOnSleep.setting()
    if (switchedOn == "0" || onCharge == "0") return ChargeDream.NOT_OURS
    val first = components.setting()?.substringBefore(',')?.trim()
    if (first == null || expandComponent(first) != ours) return ChargeDream.NOT_OURS
    return if (switchedOn == "1" && onCharge == "1") ChargeDream.OURS else ChargeDream.UNKNOWN
}

private fun String?.setting(): String? = this?.trim()?.takeUnless { it.isEmpty() || it == "null" }

/** "pkg/.Cls" is short for "pkg/pkg.Cls". */
private fun expandComponent(flattened: String): String {
    val pkg = flattened.substringBefore('/')
    val cls = flattened.substringAfter('/', missingDelimiterValue = "")
    return if (cls.startsWith('.')) "$pkg/$pkg$cls" else flattened
}
