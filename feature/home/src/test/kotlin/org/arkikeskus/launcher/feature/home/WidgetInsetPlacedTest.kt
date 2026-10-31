package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.SizeF
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.emptyFlow
import org.arkikeskus.launcher.ui.HomeDragController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * "Widget edges align with icons" does not touch the layout, so a widget placed before it was turned on may
 * fill its cells exactly. It must not be squeezed below the smallest size its provider supports (one that
 * cannot resize could not even be made bigger by hand): it gets only the inset its own minimum leaves room
 * for, and the rest of the widgets all of it. The grid here is 90 x 100 dp cells with a 52 dp icon, so the
 * full inset is 19 dp each way and a widget of 2 x 2 cells spans 180 x 200 dp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w400dp-h700dp")
class WidgetInsetPlacedTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val drag = HomeDragController()

    /** A real host view that records the size its provider is told about. */
    private class RecordingHostView(context: Context) : AppWidgetHostView(context) {
        val reported = mutableListOf<SizeF>()

        override fun updateAppWidgetSize(newOptions: Bundle, newSizes: List<SizeF>) {
            reported += newSizes
        }
    }

    private fun provider(
        minWidth: Int = 1, minHeight: Int = 1, minResizeWidth: Int = 0,
        resizeMode: Int = AppWidgetProviderInfo.RESIZE_NONE,
    ) = AppWidgetProviderInfo().also {
        it.provider = ComponentName("p", "p.Widget")
        it.minWidth = minWidth
        it.minHeight = minHeight
        it.minResizeWidth = minResizeWidth
        it.resizeMode = resizeMode
    }

    private fun place(info: AppWidgetProviderInfo, alignWithIcons: Boolean = true): RecordingHostView {
        shadowOf(AppWidgetManager.getInstance(context)).addBoundWidget(WIDGET_ID, info)
        val view = RecordingHostView(context)
        compose.setContent {
            Workspace(
                pageCount = 1,
                columns = 4,
                rows = 6,
                entries = listOf(PlacedWidget(1L, WIDGET_ID, ComponentName("p", "p.Widget"), page = 0, cellX = 0, cellY = 0, spanX = 2, spanY = 2)),
                widgetViews = mapOf(WIDGET_ID to view),
                widgetEdgesAlignWithIcons = alignWithIcons,
                badges = emptyMap(),
                badgeShowCount = false,
                badgeScale = 1f,
                showLabels = false,
                showPageIndicator = false,
                locked = false,
                homeSignals = emptyFlow(),
                homePage = 0,
                dragController = drag,
                widgetDragController = remember { WidgetDragController(drag) },
                onAddWidget = { _, _ -> },
                onAppClick = {},
                onAppMenu = { _, _, _ -> },
                onMove = { _, _, _, _ -> true },
                onMoveFolder = { _, _, _, _ -> true },
                onDropOnDock = { _, _ -> },
                onRemoveFromHome = {},
                onOpenFolder = {},
                onLaunchShortcut = {},
                onRemoveShortcut = {},
                onCreateFolder = { _, _ -> },
                onAddToFolder = { _, _ -> },
                onEmptyAreaMenu = { _, _ -> },
                modifier = Modifier.size(360.dp, 600.dp),
            )
        }
        compose.waitForIdle()
        return view
    }

    @Test fun aWidgetThatNeedsItsWholeWidthKeepsIt() {
        val view = place(provider(minWidth = 180))

        assertThat(view.width).isEqualTo(180)
        assertThat(view.height).isEqualTo(162)
        assertThat(view.reported.last()).isEqualTo(SizeF(180f, 162f))
    }

    @Test fun aWidgetThatNeedsMostOfItsHeightGetsOnlyTheInsetLeft() {
        val view = place(provider(minHeight = 190))

        assertThat(view.width).isEqualTo(142)
        assertThat(view.height).isEqualTo(190)
        assertThat(view.reported.last()).isEqualTo(SizeF(142f, 190f))
    }

    @Test fun aWidgetWithRoomGetsTheFullInset() {
        val view = place(provider(minWidth = 100, minHeight = 100))

        assertThat(view.width).isEqualTo(142)
        assertThat(view.height).isEqualTo(162)
        assertThat(view.reported.last()).isEqualTo(SizeF(142f, 162f))
    }

    @Test fun aWidgetThatResizesCountsTheSmallestSizeItSupports() {
        val view = place(provider(minWidth = 180, minResizeWidth = 100, resizeMode = AppWidgetProviderInfo.RESIZE_HORIZONTAL))

        assertThat(view.width).isEqualTo(142)
    }

    @Test fun aSmallerResizeSizeCountsOnlyWhenTheWidgetResizesThatWay() {
        val view = place(provider(minWidth = 180, minResizeWidth = 100, resizeMode = AppWidgetProviderInfo.RESIZE_VERTICAL))

        assertThat(view.width).isEqualTo(180)
    }

    @Test fun anOldLayoutAlreadyBelowItsMinimumFillsItsCells() {
        val view = place(provider(minWidth = 250))

        assertThat(view.width).isEqualTo(180)
    }

    @Test fun offTheWidgetFillsItsCellsWhateverItsMinimum() {
        val view = place(provider(minWidth = 100), alignWithIcons = false)

        assertThat(view.width).isEqualTo(180)
        assertThat(view.height).isEqualTo(200)
    }

    /** The provider's sizes are in pixels: on a real screen they count in dp. */
    @Test fun theSmallestSizeIsInDp() {
        assertThat(smallestWidgetSizeDp(provider(minWidth = 450, minHeight = 250), 2.5f)).isEqualTo(180f to 100f)
    }

    private companion object {
        const val WIDGET_ID = 42
    }
}
