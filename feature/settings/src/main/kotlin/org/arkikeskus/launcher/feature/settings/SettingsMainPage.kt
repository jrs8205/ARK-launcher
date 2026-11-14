package org.arkikeskus.launcher.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.ui.LauncherIcons
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette
import org.arkikeskus.launcher.ui.expressive.ExpressiveGroup
import org.arkikeskus.launcher.ui.expressive.GroupPosition
import org.arkikeskus.launcher.ui.expressive.LocalInExpressiveGroup
import org.arkikeskus.launcher.ui.expressive.expressiveGroupRow
import org.arkikeskus.launcher.ui.expressive.groupShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.graphics.graphicsLayer
import org.arkikeskus.launcher.ui.expressive.rememberPressScale

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
    /** The stored base colour, named on the Appearance row. */
    baseColour: String = LauncherSettings.BASE_COLOUR_DEFAULT,
) {
    val p = LocalExpressivePalette.current
    val context = LocalContext.current
    SearchBack(query = query, onClear = { onQueryChange("") })
    // The categories form one grouped list; the default-launcher row above them is a group of its own.
    ExpressiveGroup(
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
            fontSize = 31.sp,
            fontWeight = FontWeight.ExtraBold,
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

            SettingsCategory.entries.forEach { category ->
                // Appearance says which colour is chosen; the other rows say what their page holds.
                val summary = if (category == SettingsCategory.APPEARANCE) {
                    stringResource(R.string.settings_cat_appearance_summary_value, stringResource(baseColourName(baseColour)))
                } else {
                    stringResource(category.summary)
                }
                CategoryRow(category, summary) { onOpen(category) }
            }

            // Support the work: a quiet section at the very end (SupportSection.kt), the browser opens the page.
            // A title and a card: not a row of a group.
            SettingRow("E1", grouped = false) { SupportSection(onOpen = { openSupportLink(context, it) }) }

            VersionLine()
        }
    }
}

/**
 * Back while something is typed in the search field: it puts the keyboard away first, then clears what was typed,
 * and only then leaves Settings.
 *
 * From Android 16 the system asks the back handler registered last. This one turns on at the first letter, with the
 * keyboard already up, so it is asked before the keyboard is: it puts the keyboard away itself. (A handler that was
 * on before the keyboard came is asked after it, and never sees that press.)
 *
 * The keyboard is asked once for each time it comes up: one that stays must not hold Back for good.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchBack(query: String, onClear: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardUp = WindowInsets.isImeVisible
    var asked by remember(keyboardUp) { mutableStateOf(false) }
    BackHandler(enabled = query.isNotEmpty()) {
        if (keyboardUp && keyboard != null && !asked) {
            asked = true
            keyboard.hide()
        } else {
            onClear()
        }
    }
}

/**
 * One category on the main page, a row of the category list: its icon on a chip of the fill, its name, a line
 * saying what is on its page, and a chevron. The whole row is one button, at least 48 dp high, read as
 * "name, summary". Outside a group it is a card on its own.
 */
@Composable
internal fun CategoryRow(
    category: SettingsCategory,
    summary: String = stringResource(category.summary),
    onClick: () -> Unit,
) {
    val p = LocalExpressivePalette.current
    val inGroup = LocalInExpressiveGroup.current
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressScale(interaction)
    val pressScale = remember(press) { { press.value } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(categoryTag(category.key))
            .then(
                // A row of a group is shaped, and shrunk under a press, by the group; on its own it does both itself.
                if (inGroup) {
                    Modifier.expressiveGroupRow(scale = pressScale)
                } else {
                    Modifier
                        .graphicsLayer { scaleX = press.value; scaleY = press.value }
                        .clip(groupShape(GroupPosition.Only))
                },
            )
            .background(p.surfaceHi)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(p.fill, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(category.icon),
                contentDescription = null,
                tint = p.onFill,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(stringResource(category.title), color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(summary, color = p.dim, fontSize = 12.5.sp)
        }
        Icon(
            painter = painterResource(LauncherIcons.ChevronRight),
            contentDescription = null,
            tint = p.faint,
            modifier = Modifier.size(22.dp),
        )
    }
}
