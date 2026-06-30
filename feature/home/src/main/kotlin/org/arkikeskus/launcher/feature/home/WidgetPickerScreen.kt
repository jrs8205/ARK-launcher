package org.arkikeskus.launcher.feature.home

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import org.arkikeskus.launcher.ui.LauncherIcons

/** A widget app group: the app label, its providers, and each provider's pre-resolved widget label
 *  (resolved once so the search filter doesn't hit the PackageManager on every keystroke). */
private data class WidgetGroup(
    val appLabel: String,
    val providers: List<AppWidgetProviderInfo>,
    val widgetLabels: List<String>,
)

/** Full-screen picker: installed widget providers grouped by app, with a search box; tap one to add. */
@Composable
fun WidgetPickerScreen(
    onPick: (AppWidgetProviderInfo) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current
    val pm = context.packageManager
    val groups = remember {
        AppWidgetManager.getInstance(context).installedProviders
            .groupBy { it.provider.packageName }
            .map { (_, providers) ->
                val sorted = providers.sortedBy { it.loadLabel(pm) }
                WidgetGroup(
                    appLabel = sorted.first().loadLabel(pm),
                    providers = sorted,
                    widgetLabels = sorted.map { it.loadLabel(pm) },
                )
            }
            .sortedBy { it.appLabel.lowercase() }
    }
    var query by remember { mutableStateOf("") }
    // Filter by app label OR widget label (case-insensitive). A whole-app match keeps all its widgets;
    // otherwise only the matching widgets are shown under that app.
    val filtered = remember(query, groups) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            groups
        } else {
            groups.mapNotNull { g ->
                if (g.appLabel.lowercase().contains(q)) {
                    g
                } else {
                    val idx = g.widgetLabels.indices.filter { g.widgetLabels[it].lowercase().contains(q) }
                    if (idx.isEmpty()) null
                    else g.copy(providers = idx.map { g.providers[it] }, widgetLabels = idx.map { g.widgetLabels[it] })
                }
            }
        }
    }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Text(
                text = stringResource(R.string.widget_picker_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.widget_search)) },
                leadingIcon = {
                    Icon(painter = painterResource(R.drawable.ic_search), contentDescription = null)
                },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        Icon(
                            painter = painterResource(LauncherIcons.Close),
                            contentDescription = null,
                            modifier = Modifier.clickable { query = "" },
                        )
                    }
                } else {
                    null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
            )
            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Text(
                        text = stringResource(R.string.widget_search_none),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 40.dp),
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    filtered.forEach { group ->
                        item(key = "h-${group.appLabel}") {
                            Text(
                                text = group.appLabel,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                        items(group.providers.size) { i ->
                            WidgetRow(provider = group.providers[i], onClick = { onPick(group.providers[i]) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetRow(provider: AppWidgetProviderInfo, onClick: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager
    val (sx, sy) = remember(provider) { defaultWidgetSpans(provider, context) }
    val preview = remember(provider) {
        val d = runCatching { provider.loadPreviewImage(context, 0) }.getOrNull()
            ?: runCatching { provider.loadIcon(context, 0) }.getOrNull()
        d?.let { runCatching { it.toBitmap().asImageBitmap() }.getOrNull() }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (preview != null) {
            Image(bitmap = preview, contentDescription = null, modifier = Modifier.size(56.dp))
        }
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(provider.loadLabel(pm), color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = "${sx}×${sy}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
}
