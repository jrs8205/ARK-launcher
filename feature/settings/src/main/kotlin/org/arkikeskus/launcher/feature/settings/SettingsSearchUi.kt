package org.arkikeskus.launcher.feature.settings

import android.content.res.Resources
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.arkikeskus.launcher.ui.LauncherIcons
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette

/** The tag of the search field on the main page. */
internal const val SEARCH_FIELD_TAG = "settings-search"

/** The tag of a row the search found. */
internal fun searchHitTag(id: String): String = "search-hit-$id"

/**
 * Every row of [SettingsIndex] this Android version can have, with its texts in the language of [resources]: what
 * the search field looks through. [sdkInt] is the device's version (a parameter for tests).
 */
internal fun searchableSettings(resources: Resources, sdkInt: Int = Build.VERSION.SDK_INT): List<SearchableSetting> =
    SettingsIndex.entries.filter { it.minSdk <= sdkInt }.map { entry ->
        SearchableSetting(
            id = entry.id,
            category = entry.category,
            title = resources.getString(entry.title),
            description = entry.description?.let(resources::getString),
            keywords = entry.keywords.map(resources::getString),
        )
    }

/** [searchableSettings] for the current language, kept until the language changes. */
@Composable
internal fun rememberSearchableSettings(): List<SearchableSetting> {
    val resources = LocalResources.current
    return remember(resources) { searchableSettings(resources) }
}

/**
 * The search field of the main page: a pill at least 48 dp high with a hint while it is empty and a clear button
 * while it is not. The text is whatever the caller holds in [query].
 */
@Composable
internal fun SettingsSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalExpressivePalette.current
    val hint = stringResource(R.string.settings_search_hint)
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(color = p.text, fontSize = 16.sp),
        cursorBrush = SolidColor(p.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = modifier
            .fillMaxWidth()
            .testTag(SEARCH_FIELD_TAG)
            .semantics { contentDescription = hint },
        decorationBox = { field ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(p.surfaceHi, RoundedCornerShape(24.dp))
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(LauncherIcons.Search),
                    contentDescription = null,
                    tint = p.dim,
                    modifier = Modifier.size(22.dp),
                )
                Box(modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 12.dp)) {
                    if (query.isEmpty()) Text(hint, color = p.dim, fontSize = 16.sp)
                    field()
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            painter = painterResource(LauncherIcons.Close),
                            contentDescription = stringResource(R.string.settings_search_clear),
                            tint = p.dim,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        },
    )
}

/**
 * What the search found, in place of the category list: a line saying how many (or that there is none), said
 * politely to a screen reader as it changes, then a row per setting: its title with the matched part marked, and
 * the page it is on. A row is one button, at least 56 dp high; [onOpen] takes the user to it.
 */
@Composable
internal fun SettingsSearchResults(hits: List<SearchHit>, onOpen: (SearchHit) -> Unit) {
    val p = LocalExpressivePalette.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (hits.isEmpty()) {
                stringResource(R.string.settings_search_none)
            } else {
                pluralStringResource(R.plurals.settings_search_count, hits.size, hits.size)
            },
            color = p.dim,
            fontSize = 12.5.sp,
            modifier = Modifier
                .padding(start = 6.dp, top = 10.dp, bottom = 6.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        hits.forEach { hit ->
            val place = stringResource(hit.setting.category?.title ?: R.string.settings_title)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(searchHitTag(hit.setting.id))
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(role = Role.Button) { onOpen(hit) }
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            ) {
                Text(
                    text = buildAnnotatedString {
                        append(hit.setting.title)
                        hit.titleMatch?.let { match ->
                            addStyle(SpanStyle(color = p.accent, fontWeight = FontWeight.Bold), match.first, match.last + 1)
                        }
                    },
                    color = p.text,
                    fontSize = 16.sp,
                )
                Text(place, color = p.dim, fontSize = 12.5.sp)
            }
        }
    }
}
