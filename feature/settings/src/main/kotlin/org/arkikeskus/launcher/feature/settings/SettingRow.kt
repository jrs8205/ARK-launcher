package org.arkikeskus.launcher.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * One row of Settings as [SettingsIndex] lists it: [content] under the row's [id], so the page and the list can be
 * held against each other. The frame adds nothing to the look.
 */
@Composable
internal fun SettingRow(id: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.testTag(settingTag(id))) { content() }
}
