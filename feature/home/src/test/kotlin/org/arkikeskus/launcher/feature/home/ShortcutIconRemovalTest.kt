package org.arkikeskus.launcher.feature.home

import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.data.RemainingPins
import org.junit.Test

/**
 * Issue #23: when a pinned shortcut leaves home, the icon picked for it goes too, unless the same
 * shortcut is still pinned elsewhere on home (a second copy keeps the icon).
 */
class ShortcutIconRemovalTest {
    private val remaining = RemainingPins("org.mozilla.firefox", 0L, listOf("https://maps.example/"))

    @Test fun theIconGoesWithTheLastCopyOfTheShortcut() {
        assertThat(shortcutIconStaysAfterRemoval("https://github.com/", remaining)).isFalse()
        assertThat(shortcutIconStaysAfterRemoval("https://github.com/", null)).isFalse()
    }

    @Test fun theIconStaysWhileAnotherCopyIsStillOnHome() {
        assertThat(shortcutIconStaysAfterRemoval("https://maps.example/", remaining)).isTrue()
    }
}
