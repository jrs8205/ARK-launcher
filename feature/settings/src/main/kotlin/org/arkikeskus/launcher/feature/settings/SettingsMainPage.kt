package org.arkikeskus.launcher.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.arkikeskus.launcher.ui.LauncherIcons
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette

/** How strongly the accent tints the tile behind a category's icon. */
internal const val CATEGORY_TILE_ALPHA = 0.16f

/**
 * The first page of Settings: the title, the search field, the default-launcher row, the categories, each opening a
 * page of its own through [onOpen], then the support block and the installed version. While something is typed in
 * the field ([query]), the rows it found ([hits]) take the place of everything below it; [onOpenHit] opens one.
 */
@Composable
internal fun SettingsMainPage(
    onOpen: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
    scroll: ScrollState = rememberScrollState(),
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    hits: List<SearchHit> = emptyList(),
    onOpenHit: (SearchHit) -> Unit = {},
) {
    val p = LocalExpressivePalette.current
    val context = LocalContext.current
    // Back clears what was typed before it leaves Settings.
    BackHandler(enabled = query.isNotEmpty()) { onQueryChange("") }
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            color = p.text,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
            modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 6.dp),
        )

        SettingsSearchField(query = query, onQueryChange = onQueryChange, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))

        if (query.isNotBlank()) {
            // Searching: the rows found take the place of everything below the field.
            SettingsSearchResults(hits = hits, onOpen = onOpenHit)
        } else {
            SettingRow("T2") { DefaultLauncherRow() }
            Spacer(Modifier.height(6.dp))

            SettingsCategory.entries.forEach { category -> CategoryRow(category) { onOpen(category) } }

            // Support the work: a quiet section at the very end (SupportSection.kt), the browser opens the page.
            SettingRow("E1") { SupportSection(onOpen = { openSupportLink(context, it) }) }

            VersionLine()
        }
    }
}

/**
 * One category on the main page: its icon on a tinted tile, its name, a line saying what is on its page, and a
 * chevron. The whole row is one button, at least 48 dp high, read as "name, summary".
 */
@Composable
internal fun CategoryRow(category: SettingsCategory, onClick: () -> Unit) {
    val p = LocalExpressivePalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(categoryTag(category.key))
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(p.accent.copy(alpha = CATEGORY_TILE_ALPHA), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(category.icon),
                contentDescription = null,
                tint = p.accent,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(stringResource(category.title), color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(category.summary), color = p.dim, fontSize = 12.5.sp)
        }
        Icon(
            painter = painterResource(LauncherIcons.ChevronRight),
            contentDescription = null,
            tint = p.faint,
            modifier = Modifier.size(22.dp),
        )
    }
}
