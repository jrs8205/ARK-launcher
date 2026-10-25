package org.arkikeskus.launcher.ui.component

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import org.arkikeskus.launcher.model.CustomIcon
import org.arkikeskus.launcher.model.CustomIconImage

/** An icon the user picked ([CustomIcon]) drawn on its own — a folder's icon, a tile in the icon
 *  picker — loaded and cached by Coil like the app icons. */
@Composable
fun PickedIcon(icon: CustomIcon, size: Dp, contentDescription: String?, modifier: Modifier = Modifier) {
    AsyncImage(model = CustomIconImage(icon), contentDescription = contentDescription, modifier = modifier.size(size))
}
