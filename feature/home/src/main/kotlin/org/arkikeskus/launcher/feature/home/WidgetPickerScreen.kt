package org.arkikeskus.launcher.feature.home

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Process
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.drawToBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.ui.LauncherIcons

private data class WidgetGroup(val packageName: String, val label: String, val widgets: List<WidgetOption>)
private data class WidgetOption(val choice: WidgetChoice, val label: String)
private data class WidgetPreviewData(val remote: RemoteViews? = null, val bitmap: Bitmap? = null)

/** Searchable preview gallery. Keep the source composed (transparent) until its pointer is released. */
@Composable
fun WidgetPickerScreen(
    dragController: WidgetDragController,
    columns: Int,
    rows: Int,
    onPick: (WidgetChoice, Int, Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    val groups by produceState<List<WidgetGroup>?>(null, context) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                AppWidgetManager.getInstance(context).installedProviders
                    .filter { it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 }
                    .groupBy { it.provider.packageName }
                    .map { (pkg, providers) ->
                        val label = runCatching {
                            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                        }.getOrDefault(pkg)
                        WidgetGroup(pkg, label, providers.map { provider ->
                            WidgetOption(WidgetChoice.App(provider), runCatching { provider.loadLabel(pm) }.getOrDefault(label))
                        }.sortedBy { it.label.lowercase() })
                    }.sortedBy { it.label.lowercase() }
            }.getOrElse { failed = true; emptyList() }
        }
    }
    val builtinTitle = stringResource(R.string.widget_builtin_section)
    val builtins = listOf(
        WidgetOption(WidgetChoice.Builtin(HomeItemEntity.BUILTIN_SMARTSPACE), stringResource(R.string.smartspace_widget_name)),
        WidgetOption(WidgetChoice.Builtin(HomeItemEntity.BUILTIN_BATTERY), stringResource(R.string.battery_widget_name)),
        WidgetOption(WidgetChoice.Builtin(HomeItemEntity.BUILTIN_NOTIFICATIONS), stringResource(R.string.notifications_widget_name)),
        WidgetOption(WidgetChoice.Builtin(HomeItemEntity.BUILTIN_PEOPLE), stringResource(R.string.people_widget_name)),
    )
    val all = listOf(WidgetGroup("builtin", builtinTitle, builtins)) + groups.orEmpty()
    val visible = all.mapNotNull { group ->
        val widgets = if (group.label.contains(query.trim(), true)) group.widgets
            else group.widgets.filter { it.label.contains(query.trim(), true) }
        group.copy(widgets = widgets).takeIf { widgets.isNotEmpty() }
    }
    val dragging = dragController.moving
    Surface(
        modifier.graphicsLayer { alpha = if (dragging) 0f else 1f },
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.widget_picker_title), style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f).padding(8.dp))
                IconButton(onClick = onDismiss) {
                    Icon(painterResource(LauncherIcons.Close), stringResource(R.string.widget_picker_close))
                }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text(stringResource(R.string.widget_search)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                trailingIcon = if (query.isEmpty()) null else ({
                    IconButton(onClick = { query = "" }) {
                        Icon(painterResource(LauncherIcons.Close), stringResource(R.string.widget_search_clear))
                    }
                }),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Text(stringResource(R.string.widget_picker_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                userScrollEnabled = !dragging,
            ) {
                if (groups == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (failed) item { Text(stringResource(R.string.widget_picker_load_failed)) }
                if (visible.isEmpty() && groups != null) item { Text(stringResource(R.string.widget_search_none)) }
                visible.forEach { group ->
                    item(key = "header:" + group.packageName) {
                        Text(group.label, style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(group.widgets, key = {
                        when (val choice = it.choice) {
                            is WidgetChoice.App -> choice.provider.provider.flattenToString()
                            is WidgetChoice.Builtin -> "builtin:" + choice.type
                        }
                    }) { option ->
                        WidgetCard(option, dragController, columns, rows, onPick)
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetCard(
    option: WidgetOption, controller: WidgetDragController, columns: Int, rows: Int,
    onPick: (WidgetChoice, Int, Int) -> Unit,
) {
    val context = LocalContext.current
    val (defaultX, defaultY) = when (val choice = option.choice) {
        is WidgetChoice.App -> defaultWidgetSpans(choice.provider, context, controller.cellWidthDp, controller.cellHeightDp)
        is WidgetChoice.Builtin -> when (choice.type) {
            HomeItemEntity.BUILTIN_BATTERY -> 1 to 1
            HomeItemEntity.BUILTIN_NOTIFICATIONS -> columns to 1
            HomeItemEntity.BUILTIN_PEOPLE -> columns to PEOPLE_DEFAULT_SPAN_Y
            else -> columns to 2
        }
    }
    // A default larger than the grid shrinks to it, as the old add path and Launcher3 do
    // (min(span, numColumns)) — refusing it made such widgets impossible to add at all.
    val sx = defaultX.coerceIn(1, columns.coerceAtLeast(1))
    val sy = defaultY.coerceIn(1, rows.coerceAtLeast(1))
    var snapshot by remember(option.choice) { mutableStateOf<(() -> Bitmap?)?>(null) }
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Box(
                // The ratio alone bounds the height. A heightIn() in front of aspectRatio() does not: a
                // tall footprint (1×1, 3×4) measured past its slot and drew over the title row below.
                Modifier.fillMaxWidth()
                    .aspectRatio((sx * controller.cellWidthDp / (sy * controller.cellHeightDp)).coerceIn(1.5f, 3f))
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .widgetDragGesture(option.choice, true, controller) { root, fraction, owner ->
                        controller.start(WidgetDrag(null, option.choice, sx, sy, fraction, snapshot?.invoke()), root, owner)
                    },
                contentAlignment = Alignment.Center,
            ) {
                WidgetPreview(option.choice, Modifier.fillMaxSize().padding(12.dp),
                    sx * controller.cellWidthDp, sy * controller.cellHeightDp, onSnapshot = { snapshot = it })
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(option.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.widget_size, sx, sy),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(onClick = { onPick(option.choice, sx, sy) }) {
                    Text(stringResource(R.string.widget_add))
                }
            }
        }
    }
}

@Composable
internal fun WidgetPreview(
    choice: WidgetChoice, modifier: Modifier, widthDp: Float, heightDp: Float,
    onSnapshot: (() -> Bitmap?) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val provider = (choice as? WidgetChoice.App)?.provider
    val preview by produceState(WidgetPreviewData(), provider) {
        if (provider == null) return@produceState
        value = withContext(Dispatchers.IO) {
            val generated = if (Build.VERSION.SDK_INT >= 35) runCatching {
                AppWidgetManager.getInstance(context).getWidgetPreview(
                    provider.provider, provider.profile, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
                )
            }.getOrNull() else null
            // RemoteViews(pkg, layout) resolves the package in THIS profile and throws when it is absent:
            // a provider uninstalled since the list loaded, or any work-profile widget. Fall back to the
            // preview image instead of taking the HOME process down.
            val remote = generated ?: if (Build.VERSION.SDK_INT >= 31 && provider.previewLayout != 0 &&
                provider.profile == Process.myUserHandle()
            ) runCatching { RemoteViews(provider.provider.packageName, provider.previewLayout) }.getOrNull() else null
            val bitmap = runCatching {
                val d = provider.loadPreviewImage(context, 0) ?: provider.loadIcon(context, 0)
                val w = d.intrinsicWidth.coerceAtLeast(1)
                val h = d.intrinsicHeight.coerceAtLeast(1)
                val scale = minOf(1f, 640f / maxOf(w, h))
                d.toBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            }.getOrNull()
            WidgetPreviewData(remote, bitmap)
        }
    }
    var failedRemote by remember(provider, preview.remote) { mutableStateOf(false) }
    when {
        choice is WidgetChoice.Builtin -> {
            BuiltinWidgetPreview(choice.type, modifier)
            SideEffect { onSnapshot { null } }
        }
        preview.remote != null && !failedRemote -> AndroidView(
            factory = { c ->
                WidgetPreviewContainer(c).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
            },
            update = { view ->
                view.onRenderFailed = { failedRemote = true }
                val w = (widthDp * density).toInt().coerceAtLeast(1)
                val h = (heightDp * density).toInt().coerceAtLeast(1)
                if (view.widgetWidth != w || view.widgetHeight != h) {
                    view.widgetWidth = w
                    view.widgetHeight = h
                    view.requestLayout()
                }
                if (view.tag !== preview.remote) {
                    view.removeAllViews()
                    view.resetRenderFailure()
                    val child = runCatching { preview.remote!!.apply(context, view) }.getOrNull()
                    if (child == null) failedRemote = true else {
                        view.addView(child, FrameLayout.LayoutParams(-1, -1))
                        view.tag = preview.remote
                    }
                }
                onSnapshot { runCatching { view.drawToBitmap() }.getOrNull() }
            },
            modifier = modifier.clearAndSetSemantics {},
        )
        preview.bitmap != null -> {
            Image(preview.bitmap!!.asImageBitmap(), null, modifier)
            SideEffect { onSnapshot { preview.bitmap } }
        }
        else -> Icon(painterResource(R.drawable.ic_widgets), null, modifier.padding(24.dp),
            tint = MaterialTheme.colorScheme.primary)
    }
}

/** Static samples share the real widget surface; previewing never starts data collectors or actions. */
@Composable
private fun BuiltinWidgetPreview(type: String, modifier: Modifier) {
    WidgetSurface(modifier) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            when (type) {
                HomeItemEntity.BUILTIN_BATTERY -> {
                    Text("75%", style = MaterialTheme.typography.displaySmall)
                    Text(stringResource(R.string.battery_widget_name), style = MaterialTheme.typography.labelMedium)
                }
                HomeItemEntity.BUILTIN_NOTIFICATIONS -> {
                    Icon(painterResource(R.drawable.ic_widgets), null, Modifier.size(32.dp))
                    Text(stringResource(R.string.notifications_widget_name), style = MaterialTheme.typography.labelMedium)
                }
                HomeItemEntity.BUILTIN_PEOPLE -> {
                    Icon(painterResource(LauncherIcons.Message), null, Modifier.size(32.dp))
                    Text(stringResource(R.string.people_widget_name), style = MaterialTheme.typography.labelMedium)
                    Text(stringResource(R.string.people_widget_desc), style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    Text("9.41", style = MaterialTheme.typography.displayMedium)
                    Text(stringResource(R.string.smartspace_widget_name), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/**
 * Measure at the real home footprint, then scale into the card without clipping its text.
 *
 * The child is a provider's preview layout — foreign code rendered inside the HOME process. Guarding
 * `RemoteViews.apply` is not enough: a layout that inflates fine can still throw while measuring
 * (circular RelativeLayout rules), laying out or drawing (a hardware bitmap on a software canvas).
 * Any such failure stops rendering the child and reports [onRenderFailed] so the card falls back to
 * the preview image; it must never reach the launcher's crash handler.
 */
internal class WidgetPreviewContainer(context: android.content.Context) : FrameLayout(context) {
    var onRenderFailed: (() -> Unit)? = null
    var widgetWidth = 1
    var widgetHeight = 1
    private var renderFailed = false

    fun resetRenderFailure() { renderFailed = false }

    private inline fun guarded(render: () -> Unit) {
        if (renderFailed) return
        try {
            render()
        } catch (e: Exception) {
            renderFailed = true
            onRenderFailed?.invoke()
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?) = true

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        guarded {
            for (index in 0 until childCount) getChildAt(index).measure(
                MeasureSpec.makeMeasureSpec(widgetWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(widgetHeight, MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val scale = minOf(width.toFloat() / widgetWidth, height.toFloat() / widgetHeight)
        guarded {
            for (index in 0 until childCount) getChildAt(index).apply {
                layout(0, 0, widgetWidth, widgetHeight)
                pivotX = 0f
                pivotY = 0f
                scaleX = scale
                scaleY = scale
                translationX = (this@WidgetPreviewContainer.width - widgetWidth * scale) / 2f
                translationY = (this@WidgetPreviewContainer.height - widgetHeight * scale) / 2f
            }
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val checkpoint = canvas.save()
        guarded { super.dispatchDraw(canvas) }
        // A child that threw mid-draw leaves its own save()s open on the shared canvas.
        canvas.restoreToCount(checkpoint)
    }
}
