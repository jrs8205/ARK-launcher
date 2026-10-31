package org.arkikeskus.launcher.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.arkikeskus.launcher.ui.expressive.ExpressiveCard
import org.arkikeskus.launcher.ui.expressive.ExpressiveSectionTitle
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette

/** Where the two support buttons lead; the same pages the README links. */
internal const val SPONSOR_URL = "https://github.com/sponsors/jrs8205"
internal const val COFFEE_URL = "https://buymeacoffee.com/jrs8205"

/**
 * The support section at the very bottom of Settings (owner decision 2026-10-31): a title, one sentence
 * and two plain buttons that hand [onOpen] a page to open in the browser. Deliberately quiet: no
 * reminders anywhere else, no in-app payment, no third-party logos (text buttons only).
 */
@Composable
internal fun SupportSection(onOpen: (String) -> Unit) {
    val p = LocalExpressivePalette.current
    Column(Modifier.fillMaxWidth()) {
        ExpressiveSectionTitle(stringResource(R.string.settings_support_title))
        // The same card as the rows above it, so the section reads as part of the list.
        ExpressiveCard {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_support_text),
                    color = p.dim,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                // A flow row: long labels (de, ru, tr) wrap the second button onto its own line instead of clipping.
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedButton(
                        onClick = { onOpen(SPONSOR_URL) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = p.text),
                    ) { Text(stringResource(R.string.settings_support_github)) }
                    OutlinedButton(
                        onClick = { onOpen(COFFEE_URL) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = p.text),
                    ) { Text(stringResource(R.string.settings_support_coffee)) }
                }
            }
        }
    }
}
