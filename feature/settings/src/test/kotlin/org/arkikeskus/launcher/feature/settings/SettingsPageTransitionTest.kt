package org.arkikeskus.launcher.feature.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Which way a Settings page comes in: from the end when it opens, from the start when it is returned to. */
class SettingsPageTransitionTest {
    @Test fun aPageIsDeeperThanTheMainPageAndASubScreenDeeperStill() {
        assertThat(screenDepth("")).isEqualTo(0)
        for (category in SettingsCategory.entries) assertThat(screenDepth(category.key)).isEqualTo(1)
        assertThat(screenDepth("sub:hidden")).isEqualTo(2)
        assertThat(screenDepth("sub:folders")).isEqualTo(2)
    }
}
