package org.arkikeskus.launcher.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.CustomIcon
import org.arkikeskus.launcher.model.IconChoices
import org.arkikeskus.launcher.model.IconPackChoice
import org.arkikeskus.launcher.ui.component.PickedIcon

/** The icon picker's data (installed packs, their icons, gallery import); provided at the launcher's
 *  root. Without it the picker offers only the original icon. */
val LocalIconChoices = staticCompositionLocalOf<IconChoices?> { null }

/** A pack's drawable name as people read it: "google_maps" → "google maps". */
internal fun iconLabel(drawable: String): String = drawable.replace('_', ' ')

private const val SUGGESTED_KEY = "suggested"

/**
 * Choosing an icon for an app or a folder, as in Nova Launcher and Lawnchair: the original icon
 * ([original] draws it), a picture from the gallery, or any icon of an installed icon pack — for an
 * [app], the pack's own match for it first. [onPick] gets the choice (null = the original).
 */
@Composable
fun IconPickerDialog(
    app: AppItem?,
    original: @Composable (Dp) -> Unit,
    onPick: (CustomIcon?) -> Unit,
    onDismiss: () -> Unit,
) {
    var pack by remember { mutableStateOf<IconPackChoice?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                val shown = pack
                if (shown == null) {
                    PickerTitle(stringResource(R.string.icon_picker_title), LauncherIcons.Close, stringResource(R.string.icon_picker_close), onDismiss)
                    IconSources(original, onPick, onOpenPack = { pack = it })
                } else {
                    PickerTitle(shown.label, LauncherIcons.ChevronRight, stringResource(R.string.icon_picker_back), back = true) { pack = null }
                    PackIcons(shown, app, onPick)
                }
            }
        }
    }
}

@Composable
private fun PickerTitle(title: String, icon: Int, iconLabel: String, onClick: () -> Unit) =
    PickerTitle(title, icon, iconLabel, back = false, onClick)

@Composable
private fun PickerTitle(title: String, icon: Int, iconLabel: String, back: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back) {
            IconButton(onClick = onClick) {
                // The chevron turned to point back.
                Icon(painterResource(icon), contentDescription = iconLabel, modifier = Modifier.size(24.dp).graphicsLayer { scaleX = -1f })
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        if (!back) {
            IconButton(onClick = onClick) { Icon(painterResource(icon), contentDescription = iconLabel) }
        }
    }
}


@Composable
private fun IconSources(original: @Composable (Dp) -> Unit, onPick: (CustomIcon?) -> Unit, onOpenPack: (IconPackChoice) -> Unit) {
    val choices = LocalIconChoices.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.icon_picture_failed)
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null || choices == null) return@rememberLauncherForActivityResult
        scope.launch {
            val icon = withContext(Dispatchers.IO) { choices.importImage(uri) }
            if (icon != null) onPick(icon) else Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
        }
    }
    val packs by produceState<List<IconPackChoice>?>(null, choices) {
        value = withContext(Dispatchers.IO) { choices?.packs().orEmpty() }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            SourceRow(stringResource(R.string.icon_original), { original(40.dp) }) { onPick(null) }
        }
        if (choices != null) {
            item {
                SourceRow(stringResource(R.string.icon_from_gallery), {
                    Icon(painterResource(LauncherIcons.Add), contentDescription = null, modifier = Modifier.size(28.dp))
                }) {
                    runCatching { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                }
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    stringResource(R.string.icon_packs),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            val list = packs
            if (list != null && list.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.icon_packs_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            items(list.orEmpty(), key = { it.packageName }) { pack ->
                SourceRow(pack.label, {
                    Icon(painterResource(LauncherIcons.ChevronRight), contentDescription = null, modifier = Modifier.size(24.dp))
                }) { onOpenPack(pack) }
            }
        }
    }
}

@Composable
private fun SourceRow(label: String, leading: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PackIcons(pack: IconPackChoice, app: AppItem?, onPick: (CustomIcon?) -> Unit) {
    val choices = LocalIconChoices.current ?: return
    var query by remember { mutableStateOf("") }
    val icons by produceState<List<String>?>(null, pack) {
        value = withContext(Dispatchers.IO) { choices.icons(pack.packageName) }
    }
    val suggested by produceState<String?>(null, pack, app) {
        value = app?.let { withContext(Dispatchers.IO) { choices.suggestion(pack.packageName, it) } }
    }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        singleLine = true,
        label = { Text(stringResource(R.string.icon_search)) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
    val all = icons.orEmpty()
    val first = suggested?.takeIf { query.isBlank() && it in all }
    // A pack lists thousands of icons: filtered again only when the search or the list changes.
    val shown = remember(all, query, first) {
        val term = query.trim()
        all.filter { it != first && (term.isEmpty() || iconLabel(it).contains(term, ignoreCase = true)) }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(76.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
    ) {
        if (first != null) {
            item(key = SUGGESTED_KEY) {
                PackIconTile(pack.packageName, first, stringResource(R.string.icon_suggested), highlighted = true, onPick)
            }
        }
        // Keyed apart from the suggestion: a pack may well have a drawable named like its key.
        items(shown, key = { "icon:$it" }) { name ->
            PackIconTile(pack.packageName, name, label = null, highlighted = false, onPick)
        }
    }
}

@Composable
private fun PackIconTile(pack: String, drawable: String, label: String?, highlighted: Boolean, onPick: (CustomIcon?) -> Unit) {
    val icon = CustomIcon.FromPack(pack, drawable)
    Column(
        Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(if (highlighted) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
            .clickable { onPick(icon) }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PickedIcon(icon, 48.dp, contentDescription = iconLabel(drawable))
        if (label != null) {
            Text(label, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
