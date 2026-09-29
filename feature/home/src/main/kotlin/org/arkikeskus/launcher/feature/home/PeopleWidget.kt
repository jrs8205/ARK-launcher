package org.arkikeskus.launcher.feature.home

import android.Manifest
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.arkikeskus.launcher.data.AppRepository
import org.arkikeskus.launcher.data.NotificationBadgeRepository
import org.arkikeskus.launcher.data.NotificationWidgetLayout
import org.arkikeskus.launcher.data.PeopleGrouping
import org.arkikeskus.launcher.data.PersonEventKind
import org.arkikeskus.launcher.data.PersonTileState
import org.arkikeskus.launcher.data.PinnedPerson
import org.arkikeskus.launcher.data.SettingsRepository
import org.arkikeskus.launcher.data.search.ContactDataSource
import org.arkikeskus.launcher.data.search.PermissionChecker
import org.arkikeskus.launcher.data.search.RawContact
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.ui.IconMenuItem
import org.arkikeskus.launcher.ui.IconMenuPopup
import org.arkikeskus.launcher.ui.LauncherIcons
import org.arkikeskus.launcher.ui.component.AppIcon
import org.arkikeskus.launcher.ui.component.ContactAvatar
import org.arkikeskus.launcher.ui.component.NotificationBadge
import javax.inject.Inject
import java.util.Date
import kotlin.math.abs

/** How long a fresh tile takes to fade from full color to its pale "still unread" tint. */
private const val FADE_MS = 2 * 60 * 60 * 1000L

/** A tile younger than this shows full color. */
private const val FRESH_MS = 15 * 60 * 1000L

/** The pale tint an unread-but-old tile settles at; it never goes grey until dismissed. */
private const val FADED_STRENGTH = 0.35f

@HiltViewModel
class PeopleWidgetViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val badgeRepository: NotificationBadgeRepository,
    private val settingsRepository: SettingsRepository,
    private val appRepository: AppRepository,
    private val contacts: ContactDataSource,
    private val permissions: PermissionChecker,
) : ViewModel() {

    /**
     * One tile: a person who is pinned, has live notifications, or both. [contact] is the contacts
     * match found for the name (null when none, or contacts aren't allowed); a pin remembers the
     * match it was made with so a quiet pinned tile can still call and open the contact.
     */
    data class Tile(
        val key: String,
        val name: String,
        val pinned: PinnedPerson?,
        val live: PersonTileState?,
        val contact: RawContact?,
        /** The launcher app behind the newest notification (null when quiet, or no launcher entry). */
        val app: AppItem? = null,
    ) {
        /** An app-grouped tile (news, deliveries, system): no person behind it, so no pin/link/call. */
        val isApp: Boolean get() = live?.newest?.kind == PersonEventKind.APP
        val hasContent: Boolean get() = live != null
        /** Everything behind the tile is waiting for the batch: shown quiet, with the delivery time. */
        val held: Boolean get() = live?.held == true
        val heldUntil: Long get() = live?.heldUntil ?: 0L
        val count: Int get() = live?.count ?: 0
        val postTime: Long get() = live?.postTime ?: 0L
        val photoUri: String? get() = contact?.photoUri ?: pinned?.photoUri?.takeIf { it.isNotEmpty() }
        val number: String? get() = contact?.number ?: pinned?.number?.takeIf { it.isNotEmpty() }
        val lookupUri: String? get() = contact?.lookupUri ?: pinned?.lookupUri?.takeIf { it.isNotEmpty() }
        val canReply: Boolean get() = live?.entries?.any { it.reply != null } == true
        val canCall: Boolean get() = number != null || live?.entries?.any { it.callBack != null } == true

        /** "Count only" hides who wrote, except for people the user pinned themselves and app tiles. */
        fun hidesName(privacy: String): Boolean =
            privacy == LauncherSettings.PRIVACY_COUNT && pinned == null && !isApp

        /** A notification's title (an app tile's headline, a mail's subject) is content, not the
         *  sender: it shows only when message text is allowed at all. */
        fun shownTitle(privacy: String): String? =
            live?.newest?.title?.takeIf { privacy == LauncherSettings.PRIVACY_ALL }
    }

    /** Whether our notification listener is enabled — re-checked on home resume. */
    val hasAccess = MutableStateFlow(isAccessGranted())

    fun refresh() {
        hasAccess.value = isAccessGranted()
    }

    private fun isAccessGranted(): Boolean = runCatching {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }.getOrDefault(false)

    val privacy: StateFlow<String> = settingsRepository.settings
        .map { it.peoplePrivacy }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LauncherSettings.PRIVACY_ALL)

    /** The user's "same person" links (alias key → target key); tiles with links can be split again. */
    val aliases: StateFlow<Map<String, String>> = settingsRepository.peopleAliases
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Contacts matches by person key; a null value records a miss so it isn't retried. */
    private val contactCache = MutableStateFlow<Map<String, RawContact?>>(emptyMap())
    private val lookupsStarted = HashSet<String>()

    /** One launcher entry per package+profile, for app tiles' icons and launch. */
    private val appsByBadgeKey: Flow<Map<String, AppItem>> = appRepository.apps.map { apps ->
        apps.groupBy { it.badgeKey }.mapValues { (_, entries) ->
            NotificationWidgetLayout.representative(entries, { it.className }) {
                val first = entries.first()
                appRepository.launchClassName(first.packageName, first.user)
            }
        }
    }

    /** Live groups with the user's aliases applied, app groups dropped when the setting is off. */
    private val liveGroups: Flow<List<PersonTileState>> = combine(
        badgeRepository.people, settingsRepository.peopleAliases, settingsRepository.settings.map { it.peopleShowApps },
    ) { grouped, aliases, showApps ->
        PeopleGrouping.merge(grouped, aliases).filter { showApps || it.newest.kind != PersonEventKind.APP }
    }

    /** Pinned people first (pin order), then people with something new, then other apps' tiles. */
    val tiles: StateFlow<List<Tile>> = combine(
        liveGroups, settingsRepository.pinnedPeople, contactCache, appsByBadgeKey,
    ) { live, pinned, found, apps ->
        val liveByKey = live.associateBy { it.personKey }
        val pinnedKeys = pinned.map { it.key }.toSet()
        val (appGroups, peopleGroups) = live.partition { it.newest.kind == PersonEventKind.APP }
        fun appOf(group: PersonTileState?): AppItem? =
            group?.newest?.let { apps["${it.packageName}/${it.userSerial}"] }
        val tiles = pinned.map { p -> Tile(p.key, p.name, p, liveByKey[p.key], found[p.key], appOf(liveByKey[p.key])) } +
            peopleGroups.filter { it.personKey !in pinnedKeys }.map { Tile(it.personKey, it.name, null, it, found[it.personKey], appOf(it)) } +
            appGroups.map { Tile(it.personKey, it.name, null, it, null, appOf(it)) }
        lookUpContacts(tiles)
        tiles
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Resolves names to contacts once each (avatar, number), only when contacts are allowed. */
    private fun lookUpContacts(tiles: List<Tile>) {
        if (!permissions.has(Manifest.permission.READ_CONTACTS)) return
        val pending = tiles.filter {
            !it.isApp && it.contact == null && it.pinned?.lookupUri.isNullOrEmpty() && lookupsStarted.add(it.key)
        }
        if (pending.isEmpty()) return
        viewModelScope.launch {
            val found = pending.associate { tile ->
                val matches = runCatching { contacts.search(tile.name, 3) }.getOrDefault(emptyList())
                tile.key to (matches.firstOrNull { it.name.equals(tile.name, ignoreCase = true) } ?: matches.singleOrNull())
            }
            contactCache.value = contactCache.value + found
        }
    }

    /** Tap: the newest notification's own action (shade parity), else the contact card / dialer. */
    fun open(tile: Tile) {
        val live = tile.live
        if (live != null) {
            val target = live.entries.firstOrNull { it.contentIntent != null }
            val intent = target?.contentIntent
            if (target != null && intent != null && sendNotificationIntent(context, intent)) {
                if (target.autoCancel) badgeRepository.cancelNotification(target.key)
                return
            }
        }
        if (!tile.isApp && openContact(tile)) return
        tile.app?.let { appRepository.launch(it) }
    }

    /** Opens the contact card when known, else the dialer with the number; false when neither. */
    fun openContact(tile: Tile): Boolean {
        val lookup = tile.lookupUri
        if (lookup != null && start(Intent(Intent.ACTION_VIEW, Uri.parse(lookup)))) return true
        val number = tile.number
        return number != null && start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
    }

    /** The dialer's own "call back" action when a missed call offered one, else dial the number. */
    fun call(tile: Tile) {
        val callBack = tile.live?.entries?.firstNotNullOfOrNull { it.callBack }
        if (callBack != null && sendNotificationIntent(context, callBack)) return
        val number = tile.number ?: return
        start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
    }

    /** Swipe dismisses delivered notifications; waiting entries stay until delivery. This also
     *  preserves legacy Android snoozes, which a listener cannot cancel through the public API. */
    fun dismiss(tile: Tile) {
        tile.live?.entries?.filter { it.heldUntil == 0L }?.forEach { badgeRepository.cancelNotification(it.key) }
    }

    /** Inline reply through the newest notification that offers one; false when the send failed. */
    fun reply(tile: Tile, text: String): Boolean {
        val action = tile.live?.entries?.firstNotNullOfOrNull { it.reply } ?: return false
        val intent = Intent()
        val results = Bundle().apply { putCharSequence(action.remoteInput.resultKey, text) }
        RemoteInput.addResultsToIntent(arrayOf(action.remoteInput), intent, results)
        return runCatching { action.intent.send(context, 0, intent) }.isSuccess
    }

    fun pin(tile: Tile) = viewModelScope.launch {
        settingsRepository.pinPerson(
            PinnedPerson(
                key = tile.key, name = tile.name,
                lookupUri = tile.lookupUri.orEmpty(), number = tile.number.orEmpty(), photoUri = tile.photoUri.orEmpty(),
            ),
        )
    }

    fun unpin(tile: Tile) = viewModelScope.launch { settingsRepository.unpinPerson(tile.key) }

    /** Merges [tile] into [target] from now on. The target's tile is where the person lives now, so
     *  a pin on the source moves to the target (creating one there if needed) and the source's
     *  contact match fills in whatever the target's pin lacked. */
    fun link(tile: Tile, target: Tile) = viewModelScope.launch {
        if (tile.key == target.key) return@launch
        settingsRepository.linkPerson(tile.key, target.key)
        val sourcePin = tile.pinned
        val targetPin = target.pinned
        val mergedPin = when {
            sourcePin != null -> (targetPin ?: PinnedPerson(key = target.key, name = target.name))
            targetPin != null && targetPin.lookupUri.isEmpty() && tile.lookupUri != null -> targetPin
            else -> null
        }?.let { pin ->
            pin.copy(
                lookupUri = pin.lookupUri.ifEmpty { tile.lookupUri.orEmpty() },
                number = pin.number.ifEmpty { tile.number.orEmpty() },
                photoUri = pin.photoUri.ifEmpty { tile.photoUri.orEmpty() },
            )
        }
        if (mergedPin != null) settingsRepository.pinPerson(mergedPin)
        if (sourcePin != null) settingsRepository.unpinPerson(tile.key)
    }

    /** Splits every alias merged into [tile] back into its own tile. */
    fun unlink(tile: Tile) = viewModelScope.launch { settingsRepository.unlinkPerson(tile.key) }

    private fun start(intent: Intent): Boolean =
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}

/**
 * The built-in people widget: conversations grouped by person as tiles that stay grey until
 * someone writes or calls, color up while the notification is unread (fading with age), and go
 * quiet again when dismissed. Pinned people keep a tile even when quiet. Tap opens the newest
 * message; swipe sideways dismisses; long-press offers reply / call / pin.
 */
@Composable
fun PeopleWidget(
    modifier: Modifier = Modifier,
    viewModel: PeopleWidgetViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val hasAccess by viewModel.hasAccess.collectAsStateWithLifecycle()
    val tiles by viewModel.tiles.collectAsStateWithLifecycle()
    val privacy by viewModel.privacy.collectAsStateWithLifecycle()

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000 - now % 60_000)
        }
    }
    LifecycleResumeEffect(Unit) {
        now = System.currentTimeMillis()
        viewModel.refresh()
        onPauseOrDispose { }
    }
    // A tile that just arrived must not read as older (or newer) than the last minute tick.
    LaunchedEffect(tiles) { now = System.currentTimeMillis() }

    val aliases by viewModel.aliases.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<Pair<PeopleWidgetViewModel.Tile, IntOffset>?>(null) }
    var replyFor by remember { mutableStateOf<PeopleWidgetViewModel.Tile?>(null) }
    var linkFor by remember { mutableStateOf<PeopleWidgetViewModel.Tile?>(null) }
    var showAll by remember { mutableStateOf(false) }
    val noIndication = remember { MutableInteractionSource() }
    val widgetDrag = LocalWidgetDragController.current
    // Where a menu opened from the "all" list anchors: the list is a dialog, so the tile's own
    // position is not on screen.
    var widgetCenter by remember { mutableStateOf(IntOffset.Zero) }

    BoxWithConstraints(
        modifier = modifier.onGloballyPositioned { c ->
            val pos = c.positionInRoot()
            widgetCenter = IntOffset((pos.x + c.size.width / 2f).toInt(), (pos.y + c.size.height / 2f).toInt())
        },
        contentAlignment = Alignment.Center,
    ) {
        when {
            !hasAccess -> HintCard(stringResource(R.string.notifications_widget_allow_access), noIndication) {
                openNotificationListenerSettings(context)
            }
            tiles.isEmpty() -> HintCard(stringResource(R.string.people_widget_empty), noIndication, onClick = null)
            else -> {
                val gap = 8.dp
                val columns = ((maxWidth + gap) / (MIN_TILE + gap)).toInt().coerceIn(2, 6)
                val tile = (maxWidth - gap * (columns - 1)) / columns
                // Rows are as tall as a tile with content needs (name, title, text, time), never
                // squeezed to the column width like a square would be.
                val rowHeight = maxOf(tile, MIN_ROW_HEIGHT)
                val maxRows = ((maxHeight + gap) / (rowHeight + gap)).toInt().coerceAtLeast(1)
                val layout = remember(tiles, columns, maxRows) {
                    PeopleLayout.pack(tiles, { if (it.hasContent) 2 else 1 }, columns, maxRows)
                }
                Box(Modifier.fillMaxSize()) {
                    layout.placed.forEach { p ->
                        key(p.item.key) {
                            PersonTile(
                                tile = p.item,
                                privacy = privacy,
                                now = now,
                                wide = p.span > 1,
                                onOpen = { viewModel.open(p.item) },
                                onDismiss = { viewModel.dismiss(p.item) },
                                onLongPress = { anchor -> menuFor = p.item to anchor },
                                interaction = noIndication,
                                modifier = Modifier
                                    .offset(x = (tile + gap) * p.col, y = (rowHeight + gap) * p.row)
                                    .size(width = tile * p.span + gap * (p.span - 1), height = rowHeight)
                                    .claimsWidgetLongPress(widgetDrag),
                            )
                        }
                    }
                    if (layout.overflow > 0) {
                        Box(
                            modifier = Modifier
                                .offset(x = (tile + gap) * layout.chipCol, y = (rowHeight + gap) * layout.chipRow)
                                .size(width = tile, height = rowHeight)
                                .clip(RoundedCornerShape(20.dp))
                                .background(widgetSurfaceColor())
                                // The hidden tiles are one tap away in the widget's own list — the
                                // shade wouldn't do: batch-held messages and quiet pinned people
                                // aren't in it.
                                .clickable(interactionSource = noIndication, indication = null) { showAll = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.people_more, layout.overflow),
                                color = widgetContentColor(),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }

    menuFor?.let { (tile, anchor) ->
        val windowHeight = LocalWindowInfo.current.containerSize.height
        val items = buildList {
            if (tile.hasContent) add(IconMenuItem(LauncherIcons.OpenInNew, stringResource(R.string.people_action_open)) { viewModel.open(tile) })
            if (tile.canReply) add(IconMenuItem(LauncherIcons.Reply, stringResource(R.string.people_action_reply)) { replyFor = tile })
            if (tile.canCall) add(IconMenuItem(LauncherIcons.Call, stringResource(R.string.people_action_call)) { viewModel.call(tile) })
            if (tile.hasContent && !tile.held) add(IconMenuItem(LauncherIcons.DoneAll, stringResource(R.string.people_action_dismiss)) { viewModel.dismiss(tile) })
            if (tile.lookupUri != null) add(IconMenuItem(LauncherIcons.Person, stringResource(R.string.people_action_contact)) { viewModel.openContact(tile) })
            if (!tile.isApp) {
                if (tile.pinned == null) add(IconMenuItem(LauncherIcons.Pin, stringResource(R.string.people_action_pin)) { viewModel.pin(tile) })
                else add(IconMenuItem(LauncherIcons.Unpin, stringResource(R.string.people_action_unpin)) { viewModel.unpin(tile) })
                if (tiles.any { !it.isApp && it.key != tile.key }) add(IconMenuItem(LauncherIcons.Link, stringResource(R.string.people_action_link)) { linkFor = tile })
                if (aliases.containsValue(tile.key)) add(IconMenuItem(LauncherIcons.LinkOff, stringResource(R.string.people_action_unlink)) { viewModel.unlink(tile) })
            }
        }
        IconMenuPopup(
            anchor = anchor,
            preferAbove = anchor.y > windowHeight / 2,
            items = items,
            onDismiss = { menuFor = null },
        )
    }

    if (showAll) {
        AllTilesDialog(
            tiles = tiles,
            privacy = privacy,
            now = now,
            onOpen = { tile ->
                showAll = false
                viewModel.open(tile)
            },
            onLongPress = { tile ->
                showAll = false
                menuFor = tile to widgetCenter
            },
            onDismiss = { showAll = false },
        )
    }

    linkFor?.let { tile ->
        LinkDialog(
            tile = tile,
            privacy = privacy,
            candidates = tiles.filter { !it.isApp && it.key != tile.key },
            onPick = { target ->
                viewModel.link(tile, target)
                linkFor = null
            },
            onDismiss = { linkFor = null },
        )
    }

    replyFor?.let { tile ->
        ReplyDialog(
            tile = tile,
            privacy = privacy,
            onSend = { text ->
                val ok = viewModel.reply(tile, text)
                if (!ok) Toast.makeText(context, R.string.people_reply_failed, Toast.LENGTH_SHORT).show()
                replyFor = null
            },
            onDismiss = { replyFor = null },
        )
    }
}

/** The narrowest a tile gets; the widget width decides how many columns that makes. */
private val MIN_TILE = 84.dp

/** Room for a wide tile's four lines at the sizes below, plus padding. */
private val MIN_ROW_HEIGHT = 108.dp

@Composable
private fun HintCard(text: String, interaction: MutableInteractionSource, onClick: (() -> Unit)?) {
    Text(
        text = text,
        color = widgetContentColor().copy(alpha = 0.85f),
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(widgetSurfaceColor(), RoundedCornerShape(24.dp))
            .then(
                if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
                else Modifier,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** 1 = full color (fresh), sliding to [FADED_STRENGTH] over [FADE_MS]; never 0 while unread. */
private fun freshness(ageMs: Long): Float {
    if (ageMs <= FRESH_MS) return 1f
    val t = ((ageMs - FRESH_MS).toFloat() / (FADE_MS - FRESH_MS)).coerceIn(0f, 1f)
    return 1f - (1f - FADED_STRENGTH) * t
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PersonTile(
    tile: PeopleWidgetViewModel.Tile,
    privacy: String,
    now: Long,
    wide: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onLongPress: (IntOffset) -> Unit,
    interaction: MutableInteractionSource,
    modifier: Modifier = Modifier,
) {
    val quietBg = widgetSurfaceColor()
    val quietFg = widgetContentColor()
    val liveBg = MaterialTheme.colorScheme.primaryContainer
    val liveFg = MaterialTheme.colorScheme.onPrimaryContainer
    // A tile whose messages wait for the batch stays quiet-colored: nothing is asking for you yet.
    val strength = if (tile.hasContent && !tile.held) freshness(now - tile.postTime) else 0f
    val bg by animateColorAsState(lerp(quietBg, liveBg, strength), label = "tileBg")
    val fg by animateColorAsState(lerp(quietFg, liveFg, strength), label = "tileFg")
    val shape = RoundedCornerShape(20.dp)
    // An old-but-unread tile keeps a thin border in the full color, so "pale" still reads as "new".
    val outlined = tile.hasContent && strength < 0.6f

    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // The drag detector is keyed on the person, not on the notifications behind the tile, so it
    // must read the latest dismiss action rather than the one captured when the gesture started.
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val swipeable = tile.hasContent && !tile.held
    var center by remember { mutableStateOf(IntOffset.Zero) }
    var widthPx by remember { mutableStateOf(1f) }

    Box(
        modifier = modifier
            .onGloballyPositioned { c ->
                val pos = c.positionInRoot()
                widthPx = c.size.width.toFloat().coerceAtLeast(1f)
                center = IntOffset((pos.x + c.size.width / 2f).toInt(), (pos.y + c.size.height / 2f).toInt())
            }
            .graphicsLayer {
                translationX = offsetX.value
                alpha = 1f - (abs(offsetX.value) / widthPx).coerceIn(0f, 0.6f)
            }
            .then(
                if (swipeable) Modifier.pointerInput(tile.key) {
                    // Sideways = dismiss; the page swipe still works from quiet tiles and the gaps.
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (abs(offsetX.value) > widthPx * 0.45f) {
                                    offsetX.animateTo(if (offsetX.value > 0) widthPx else -widthPx)
                                    currentOnDismiss()
                                    offsetX.snapTo(0f)
                                } else {
                                    offsetX.animateTo(0f)
                                }
                            }
                        },
                        onDragCancel = { scope.launch { offsetX.animateTo(0f) } },
                    ) { change, dx ->
                        change.consume()
                        scope.launch { offsetX.snapTo(offsetX.value + dx) }
                    }
                } else Modifier,
            )
            .clip(shape)
            .background(bg)
            .then(if (outlined) Modifier.border(1.5.dp, liveBg, shape) else Modifier)
            .combinedClickable(
                interactionSource = interaction, indication = null,
                onClick = onOpen, onLongClick = { onLongPress(center) },
            )
            .padding(10.dp),
    ) {
        val hideName = tile.hidesName(privacy)
        val shownName = tile.shownName(privacy)
        if (!wide) {
            // Quiet (or squeezed) tile: avatar over the name, centered.
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(contentAlignment = Alignment.TopEnd) {
                    Avatar(tile, hideName, 32.dp)
                    NotificationBadge(count = tile.count, showCount = true, scale = 0.9f)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = shownName, color = fg, fontSize = 12.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(tile, hideName, 26.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = shownName, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    NotificationBadge(count = tile.count, showCount = true, scale = 0.9f)
                }
                Spacer(Modifier.height(4.dp))
                val appTitle = tile.shownTitle(privacy)
                Column(Modifier.weight(1f)) {
                    if (appTitle != null) {
                        Text(
                            text = appTitle, color = fg, fontSize = 13.sp, lineHeight = 17.sp,
                            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = previewText(tile, privacy),
                        color = fg.copy(alpha = 0.9f),
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        maxLines = if (appTitle != null) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = tileTimeLabel(tile, now),
                    color = fg.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Avatar(tile: PeopleWidgetViewModel.Tile, anonymous: Boolean, size: Dp) {
    val app = tile.app
    val isMail = tile.live?.newest?.kind == PersonEventKind.EMAIL
    if (tile.isApp && app != null) {
        AppIcon(appItem = app, labelColor = Color.White, showLabel = false, iconSize = size, badgeCount = 0, badgeShowCount = false)
    } else if (tile.isApp) {
        Icon(
            painter = painterResource(R.drawable.ic_notification_generic), contentDescription = null,
            tint = widgetContentColor(), modifier = Modifier.size(size),
        )
    } else if (isMail && app != null && tile.photoUri == null) {
        // A mail sender is rarely a contact with a photo; the mail app's icon says more than an initial.
        AppIcon(appItem = app, labelColor = Color.White, showLabel = false, iconSize = size, badgeCount = 0, badgeShowCount = false)
    } else if (anonymous) {
        Box(
            modifier = Modifier.size(size).clip(RoundedCornerShape(50)).background(widgetContentColor().copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(LauncherIcons.Message), null, Modifier.size(size * 0.55f), tint = widgetContentColor())
        }
    } else {
        // The person's avatar — the contact photo, else the picture the notification carried
        // (a chat's contact or group picture), else an initial — with the app the newest message
        // came from in the corner.
        val newest = tile.live?.newest
        val carried = if (tile.photoUri == null && newest?.personIcon != null) rememberAvatarBitmap(newest, size) else null
        Box(Modifier.size(size)) {
            if (carried != null) {
                Image(
                    bitmap = carried, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size).clip(CircleShape),
                )
            } else {
                ContactAvatar(name = tile.name, photoUri = tile.photoUri, size = size)
            }
            if (app != null && tile.hasContent) {
                Box(Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp)) {
                    AppIcon(
                        appItem = app, labelColor = Color.White, showLabel = false,
                        iconSize = size * 0.5f, badgeCount = 0, badgeShowCount = false,
                    )
                }
            }
        }
    }
}

/** Rasterises the notification's avatar icon at [size]; null until decoded or on any failure
 *  (the initial shows). Decoded off the main thread: a URI or resource icon reads from a content
 *  provider or an APK, and each listener refresh may bring a new one for every tile. */
@Composable
private fun rememberAvatarBitmap(entry: org.arkikeskus.launcher.data.PersonEntry, size: Dp): ImageBitmap? {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(entry.key, entry.postTime, px) {
        val icon = entry.personIcon
        bitmap = withContext(Dispatchers.IO) {
            runCatching { icon?.loadDrawable(context)?.toBitmap(width = px, height = px)?.asImageBitmap() }.getOrNull()
        }
    }
    return bitmap
}

/** "Batch at 17:00" while held, "now" within the first minute, else how long ago it arrived. */
@Composable
private fun tileTimeLabel(tile: PeopleWidgetViewModel.Tile, now: Long): String = when {
    tile.held ->
        stringResource(R.string.people_held_until, DateFormat.getTimeFormat(LocalContext.current).format(Date(tile.heldUntil)))
    now - tile.postTime < DateUtils.MINUTE_IN_MILLIS -> stringResource(R.string.people_time_now)
    else -> DateUtils.getRelativeTimeSpanString(
        tile.postTime, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
}

/** What the wide tile says under the name, honoring the privacy setting. */
@Composable
private fun previewText(tile: PeopleWidgetViewModel.Tile, privacy: String): String {
    val newest = tile.live?.newest ?: return ""
    val kindLabel = stringResource(
        when (newest.kind) {
            PersonEventKind.MISSED_CALL -> R.string.people_kind_missed_call
            PersonEventKind.EMAIL -> R.string.people_kind_email
            PersonEventKind.MESSAGE -> R.string.people_kind_message
            PersonEventKind.APP -> R.string.people_kind_app
        },
    )
    if (newest.kind == PersonEventKind.APP) {
        // The title line already shows in "sender only"; the text is the private part.
        return if (privacy == LauncherSettings.PRIVACY_ALL) newest.text?.takeIf { it.isNotBlank() } ?: kindLabel else kindLabel
    }
    // In a group the tile is the group; the sender goes in front of the text ("Mikko: Oletko…").
    val sender = newest.sender?.takeIf { it.isNotBlank() }
    if (privacy != LauncherSettings.PRIVACY_ALL) return if (privacy == LauncherSettings.PRIVACY_SENDER && sender != null) sender else kindLabel
    if (newest.kind == PersonEventKind.MISSED_CALL) return kindLabel
    val body = newest.text?.takeIf { it.isNotBlank() } ?: kindLabel
    return if (sender != null) "$sender: $body" else body
}

/** Every tile as a list, for what the widget's footprint couldn't fit. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AllTilesDialog(
    tiles: List<PeopleWidgetViewModel.Tile>,
    privacy: String,
    now: Long,
    onOpen: (PeopleWidgetViewModel.Tile) -> Unit,
    onLongPress: (PeopleWidgetViewModel.Tile) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.people_all_title)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                tiles.forEach { tile ->
                    val hideName = tile.hidesName(privacy)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(onClick = { onOpen(tile) }, onLongClick = { onLongPress(tile) })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(tile, hideName, 32.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = tile.shownName(privacy),
                                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (tile.hasContent) {
                                // One line here where the tile has two: the list is narrow.
                                val line = listOfNotNull(
                                    tile.shownTitle(privacy),
                                    previewText(tile, privacy).takeIf { it.isNotBlank() },
                                ).joinToString(" · ")
                                if (line.isNotEmpty()) Text(line, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(text = tileTimeLabel(tile, now), fontSize = 12.sp, maxLines = 1)
                            }
                        }
                        NotificationBadge(count = tile.count, showCount = true, scale = 0.9f)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.people_reply_cancel)) }
        },
    )
}

/** Picks the tile this one should merge into from now on. */
@Composable
internal fun LinkDialog(
    tile: PeopleWidgetViewModel.Tile,
    privacy: String,
    candidates: List<PeopleWidgetViewModel.Tile>,
    onPick: (PeopleWidgetViewModel.Tile) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.people_link_title, tile.shownName(privacy))) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.people_link_hint), fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                candidates.forEach { c ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(c, c.hidesName(privacy), 28.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(c.shownName(privacy), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.people_reply_cancel)) }
        },
    )
}

@Composable
private fun PeopleWidgetViewModel.Tile.shownName(privacy: String): String =
    if (hidesName(privacy)) stringResource(R.string.people_hidden_name) else name

@Composable
internal fun ReplyDialog(
    tile: PeopleWidgetViewModel.Tile,
    privacy: String,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.people_reply_title, tile.shownName(privacy))) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.people_reply_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onSend(text.trim()) }) {
                Text(stringResource(R.string.people_reply_send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.people_reply_cancel)) }
        },
    )
}
