package org.arkikeskus.launcher.ui.component

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import org.arkikeskus.launcher.model.CustomIcon
import org.arkikeskus.launcher.model.CustomIconImage

/** An icon the user picked ([CustomIcon]) drawn on its own — a folder's icon, a tile in the icon
 *  picker — loaded and cached by Coil like the app icons. When it can't be loaded (its pack was
 *  uninstalled, its picture deleted) [fallback] shows instead, if given. Its pack's icon epoch (see
 *  [LocalIconEpochs]) is in the model, so an updated or reinstalled pack loads it anew. */
@Composable
fun PickedIcon(
    icon: CustomIcon,
    size: Dp,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    fallback: (@Composable () -> Unit)? = null,
) {
    val epochs = LocalIconEpochs.current
    val epoch = when (icon) {
        is CustomIcon.FromPack -> epochs.of(icon.pack)
        is CustomIcon.FromFile -> epochs.global
    }
    var failed by remember(icon, epoch) { mutableStateOf(false) }
    if (failed && fallback != null) {
        fallback()
        return
    }
    AsyncImage(
        model = CustomIconImage(icon, epoch),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        onError = { failed = true },
    )
}
