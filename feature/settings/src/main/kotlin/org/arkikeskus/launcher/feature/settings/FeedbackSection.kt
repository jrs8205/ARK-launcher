package org.arkikeskus.launcher.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.arkikeskus.launcher.ui.expressive.ExpressiveActionRow

internal const val DISCUSSIONS_URL = "https://github.com/jrs8205/ARK-launcher/discussions"
internal const val TELEGRAM_URL = "https://t.me/ARKLauncherUpdates"
internal const val REDDIT_URL = "https://www.reddit.com/r/ArkLauncher/"

/**
 * The rows of Settings ▸ Feedback: every channel the README lists, each handing [onOpen] its page to
 * open in the browser. GitHub Discussions, the Telegram channel (release news, in place of the group
 * since 1.0.3) and, since 1.0.2, the r/ArkLauncher
 * community (owner decision 2026-11-05).
 */
@Composable
internal fun FeedbackRows(onOpen: (String) -> Unit) {
    SettingRow("FB1") {
        ExpressiveActionRow(
            label = stringResource(R.string.settings_feedback_discussions),
            description = stringResource(R.string.settings_feedback_discussions_desc),
        ) { onOpen(DISCUSSIONS_URL) }
    }
    SettingRow("FB2") {
        ExpressiveActionRow(
            label = stringResource(R.string.settings_feedback_telegram),
            description = stringResource(R.string.settings_feedback_telegram_desc),
        ) { onOpen(TELEGRAM_URL) }
    }
    SettingRow("FB3") {
        ExpressiveActionRow(
            label = stringResource(R.string.settings_feedback_reddit),
            description = stringResource(R.string.settings_feedback_reddit_desc),
        ) { onOpen(REDDIT_URL) }
    }
}
