package org.arkikeskus.launcher.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.arkikeskus.launcher.ui.expressive.Accent

/** A round contact avatar: the photo when available, otherwise an initial on an accent circle. */
@Composable
fun ContactAvatar(name: String, photoUri: String?, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    if (!photoUri.isNullOrEmpty()) {
        AsyncImage(
            model = photoUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(CircleShape),
        )
    } else {
        Box(
            modifier = modifier.size(size).clip(CircleShape).background(Accent.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initialOf(name),
                color = Color.White, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** The avatar's letter: the first whole character of [name], upper-cased. An emoji or any other
 *  character outside the basic plane is two UTF-16 units, and its first unit alone drew as a box. */
internal fun initialOf(name: String): String {
    if (name.isEmpty()) return "?"
    return String(Character.toChars(name.codePointAt(0))).uppercase()
}
