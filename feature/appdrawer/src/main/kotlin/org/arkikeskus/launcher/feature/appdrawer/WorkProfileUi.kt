package org.arkikeskus.launcher.feature.appdrawer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.arkikeskus.launcher.ui.LauncherIcons
import org.arkikeskus.launcher.ui.expressive.Accent
import org.arkikeskus.launcher.ui.expressive.ExpressiveCard
import org.arkikeskus.launcher.ui.expressive.LocalExpressivePalette

/**
 * Personal / Work as two equal pill segments under the search field: the selected one is filled
 * with the drawer's accent, the other sits on the search pill's surface. Each segment is a tab for
 * accessibility. The row is 40 dp but grows with its labels: at a large font size on a narrow
 * screen a label wraps to two lines, and a fixed height cut the second line off.
 */
@Composable
internal fun DrawerTabRow(selected: DrawerTab, onSelect: (DrawerTab) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalExpressivePalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp)
            .height(IntrinsicSize.Max)
            .clip(RoundedCornerShape(20.dp))
            .background(palette.surfaceHi)
            .selectableGroup(),
    ) {
        DrawerTab.entries.forEach { tab ->
            val isSelected = tab == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (isSelected) Accent else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(tab) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(
                        if (tab == DrawerTab.Personal) R.string.drawer_tab_personal else R.string.drawer_tab_work,
                    ),
                    color = if (isSelected) Color.White else palette.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * The tab row over the drawer's pager. While the pager is on screen it owns the tab: a tap animates
 * the pager there and the row highlights the pager's target page at once, and every time the pager
 * comes to rest, the page it rests on goes to [onSelectTab], which the ViewModel keeps as the stored
 * tab (the initial page of a pager rebuilt after a search). The stored tab never drives the pager:
 * a two-way sync raced itself. A tab tapped back late in the animation cancelled it, the momentary
 * rest reported the page it had reached, and the pager went back there over the tap; and a drag that
 * interrupted the animation and settled on the page it started from never reported at all, since
 * the settled page had not changed.
 */
@Composable
internal fun PagerTabRow(pagerState: PagerState, onSelectTab: (DrawerTab) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    DrawerTabRow(
        selected = DrawerTab.entries[pagerState.targetPage],
        onSelect = { tab -> scope.launch { pagerState.animateScrollToPage(tab.ordinal) } },
        modifier = modifier,
    )
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) onSelectTab(DrawerTab.entries[pagerState.currentPage])
        }
    }
}

/**
 * The Work page: the paused card, or the work [grid] with the floating pause button over it. The
 * grid is handed the button's MEASURED height plus its margin as bottom padding, so the last row
 * is never left under the button — a long label at a large font size makes it several lines tall.
 */
@Composable
internal fun WorkPage(
    workProfile: WorkProfileUi,
    onSetWorkPaused: (Boolean) -> Unit,
    grid: @Composable (bottomPadding: Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (workProfile.paused) {
            WorkPausedCard(
                canPause = workProfile.canPause,
                enabled = !workProfile.transitioning,
                onUnpause = { onSetWorkPaused(false) },
            )
        } else {
            val density = LocalDensity.current
            var buttonHeightPx by remember { mutableIntStateOf(0) }
            val reserved = when {
                !workProfile.canPause -> 0.dp
                buttonHeightPx == 0 -> PauseButtonMinHeight + PauseButtonMargin // until measured
                else -> with(density) { buttonHeightPx.toDp() } + PauseButtonMargin
            }
            grid(reserved)
            if (workProfile.canPause) {
                PauseWorkAppsButton(
                    enabled = !workProfile.transitioning,
                    onClick = { onSetWorkPaused(true) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = PauseButtonMargin)
                        .onSizeChanged { buttonHeightPx = it.height },
                )
            }
        }
    }
}

private val PauseButtonMinHeight = 48.dp
private val PauseButtonMargin = 16.dp

/** The Work page's floating pill: pauses the work profile (quiet mode). Disabled while a request is in flight. */
@Composable
internal fun PauseWorkAppsButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(24.dp),
        colors = accentButtonColors(),
        modifier = modifier.heightIn(min = PauseButtonMinHeight),
    ) {
        Icon(painter = painterResource(LauncherIcons.Work), contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.work_apps_pause))
    }
}

/**
 * What the Work page shows while the profile is paused: the work glyph, the title, one line of
 * body and, when the profile accepts pause requests, the "Unpause" button (disabled while a
 * request is in flight). Fills its page so the card sits in the middle.
 *
 * The page is a scroll container although the card fits: the drawer's pull-to-close is a
 * nested-scroll parent, and only a scrollable child hands it a downward drag. It also keeps the
 * card reachable in landscape or at a large font size.
 */
@Composable
internal fun WorkPausedCard(canPause: Boolean, enabled: Boolean, onUnpause: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalExpressivePalette.current
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val pageHeight = maxHeight
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = pageHeight)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
        ExpressiveCard {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painter = painterResource(LauncherIcons.Work),
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.work_apps_paused_title),
                    color = palette.text,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.work_apps_paused_body),
                    color = palette.dim,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                if (canPause) {
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onUnpause,
                        enabled = enabled,
                        shape = RoundedCornerShape(24.dp),
                        colors = accentButtonColors(),
                    ) {
                        Text(stringResource(R.string.work_apps_unpause))
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun accentButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Accent,
    contentColor = Color.White,
    disabledContainerColor = Accent.copy(alpha = 0.5f),
    disabledContentColor = Color.White.copy(alpha = 0.7f),
)
