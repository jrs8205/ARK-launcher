package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.math.roundToInt
import kotlinx.coroutines.test.TestScope
import org.arkikeskus.launcher.launcher.system.BatteryMonitor
import org.arkikeskus.launcher.model.AppItem
import org.arkikeskus.launcher.model.HomeProfile
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.model.WallpaperLook
import org.arkikeskus.launcher.model.WidgetPlacement
import org.arkikeskus.launcher.ui.HomeDragController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the home screen draws around a switch of the home profile: nothing of the profile that left, and
 * nothing it had open or remembered of it. The state the screen holds and the news of the switch arrive
 * apart, in either order, and the state can be an old one kept from before the screen was last shown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class HomeOfActiveProfileTest {
    @get:Rule val compose = createComposeRule()

    private fun loaded(profile: Long) = HomeUiState(loaded = true, homeProfileId = profile)

    @Test fun onlyAnInPlaceSwitchKeepsThePreviousWholeFrameWhileTheTargetLoads() {
        val first = loaded(1)
        val second = loaded(2)
        assertThat(homeSwitchFrame(2, first, 1, first)).isSameInstanceAs(first)
        assertThat(homeSwitchFrame(1, second, 1, first)).isSameInstanceAs(first)
        assertThat(homeSwitchFrame(2, second, 1, first)).isSameInstanceAs(second)
        assertThat(homeSwitchFrame(2, first, null, first)).isNull()
    }

    @Test fun theShellKeepsItsRememberedObjectBeforeRowsAndAcrossBothKindsOfSwitch() {
        val next = mutableStateOf(HomeUiState())
        val source = mutableStateOf<Long?>(null)
        val finished = mutableStateOf<Long?>(null)
        val layOutTarget = mutableStateOf(false)
        val shells = mutableListOf<Any>()
        val ended = mutableListOf<Long?>()
        compose.setContent {
            VisibleHomeFrame(active.value, next.value, source.value, finished.value, {
                ended += finished.value
                source.value = null
                finished.value = null
            }) { frame, layout ->
                val shell = remember { Any().also { shells += it } }
                Text("shell ${shells.indexOf(shell)}, home ${frame?.homeProfileId}",
                    if (frame?.homeProfileId != 2L || layOutTarget.value) layout else Modifier)
            }
        }
        compose.onNodeWithText("shell 0, home null").assertIsDisplayed()
        next.value = loaded(1)
        compose.onNodeWithText("shell 0, home 1").assertIsDisplayed()
        compose.runOnIdle { source.value = 1; active.value = 2; finished.value = 2 }
        compose.onNodeWithText("shell 0, home 1").assertIsDisplayed()
        assertThat(ended).isEmpty()
        next.value = loaded(2)
        compose.onNodeWithText("shell 0, home 2").assertIsDisplayed()
        assertThat(ended).isEmpty() // Rows have arrived, but the destination has not been laid out yet.
        layOutTarget.value = true
        compose.waitForIdle()
        assertThat(ended).containsExactly(2L)
        active.value = 1 // Settings, while the rows still belong to profile 2.
        compose.onNodeWithText("shell 0, home null").assertIsDisplayed()
        next.value = loaded(1)
        compose.onNodeWithText("shell 0, home 1").assertIsDisplayed()
        assertThat(shells).hasSize(1)
    }

    @Test fun stoppingDuringAnInPlaceSwitchDropsTheHeldHomeBeforeSettingsReturns() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.RESUMED
        val source = mutableStateOf<Long?>(1)
        var stops = 0
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                VisibleHomeFrame(active.value, state.value, source.value, null, { stops++ }) { frame, layout ->
                    Text("home ${frame?.homeProfileId}", layout)
                }
            }
        }
        compose.onNodeWithText("home 1").assertIsDisplayed()
        active.value = 2
        compose.onNodeWithText("home 1").assertIsDisplayed()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        assertThat(stops).isEqualTo(1)
        compose.onNodeWithText("home null").assertIsDisplayed()
        state.value = loaded(2)
        compose.onNodeWithText("home 2").assertIsDisplayed()
    }

    @OptIn(InternalComposeUiApi::class)
    @Test fun theFirstFrameWithTheNewDockAlreadyHasTheNewGridAtItsFullSize() {
        val context: Application = RuntimeEnvironment.getApplication()
        val chargeGlow = ChargeGlowViewModel(BatteryMonitor(context))
        val wiring = ProductionWiring(context, TestScope(), inMemorySettings = true)
        val vm = whileTheMainLooperTurns { wiring.homeViewModel() }
        fun screen(id: Long): HomeUiState {
            val grid = PlacedFolder(10, "Grid $id", emptyList(), 0, 2, 2)
            val dock = grid.copy(id = 11, name = "Dock $id", page = DOCK_PAGE, cellX = 0, cellY = 0)
            return loaded(id).copy(
                settings = LauncherSettings(homeProfileId = id,
                    homeColumns = if (id == 1L) 4 else 5, dockColumns = 7, showDockLabels = true,
                    defaultReminderDismissed = true),
                dockEntries = if (id == 1L) listOf(DockFolder(dock)) else List(7) { slot ->
                    DockFolder(dock.copy(id = 20L + slot, name = if (slot == 0) dock.name else "Slot $slot", cellX = slot))
                },
                entries = listOf(grid, PlacedBuiltin(12, "profile_switch:99", 0, 1, 0, 1, 1)),
            )
        }
        val present = mutableStateOf(true)
        val next = mutableStateOf(screen(1))
        val layouts = mutableListOf<Map<String, Rect>>()
        fun labels(root: SemanticsNode): Map<String, Rect> = buildMap {
            fun visit(node: SemanticsNode) {
                node.config.getOrNull(SemanticsProperties.Text)?.forEach { put(it.text, node.boundsInRoot) }
                node.children.forEach(::visit)
            }
            visit(root)
        }
        try {
            compose.setContent {
                val root = LocalView.current as ViewRootForTest
                if (present.value) VisibleHomeFrame(active.value, next.value, 1, null, {}) { frame, layout ->
                    if (frame != null) HomeScreen(uiState = frame, onOpenDrawer = {}, onOpenSettings = {},
                        onManageProfiles = {}, modifier = layout.size(350.dp, 600.dp).onGloballyPositioned {
                            layouts += labels(root.semanticsOwner.unmergedRootSemanticsNode)
                        }, viewModel = vm, chargeGlow = chargeGlow)
                }
            }
            compose.onNodeWithText("Grid 1").assertIsDisplayed()
            val oldGrid = compose.onNodeWithText("Grid 1").fetchSemanticsNode().boundsInRoot
            assertThat(oldGrid.width).isGreaterThan(1f)
            active.value = 2
            compose.onNodeWithText("Grid 1").assertIsDisplayed()
            compose.onNodeWithText("Dock 1").assertIsDisplayed()
            compose.onNodeWithText("Switch profile", useUnmergedTree = true).assertIsDisplayed()
            layouts.clear()
            next.value = screen(2)
            compose.onNodeWithText("Dock 2").assertIsDisplayed()
            val first = layouts.first()
            val newDock = compose.onNodeWithText("Dock 2", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertThat(newDock.width).isGreaterThan(1f)
            assertThat(first["Dock 2"]).isEqualTo(newDock)
            val newGrid = compose.onNodeWithText("Grid 2").fetchSemanticsNode().boundsInRoot
            assertThat(newGrid.left).isLessThan(oldGrid.left)
            assertThat(newGrid.width).isGreaterThan(1f)
            assertThat(first["Grid 2"]).isEqualTo(newGrid)
            val newWidget = compose.onNodeWithText("Switch profile", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertThat(newWidget.width).isGreaterThan(1f)
            assertThat(first["Switch profile"]).isEqualTo(newWidget)
            assertThat(first.keys).containsNoneOf("Dock 1", "Grid 1")
            present.value = false
            compose.waitForIdle()
        } finally {
            wiring.clear(vm)
            wiring.close()
        }
    }

    private val active = mutableStateOf<Long?>(1L)
    private val state = mutableStateOf(loaded(1))

    /** A home screen with something that can be opened, as a folder is. */
    private fun show() = compose.setContent {
        HomeOfActiveProfile(active = active.value, uiState = state.value) { _, shown ->
            var open by remember { mutableStateOf(false) }
            Column {
                Text(if (shown.loaded) "home of ${shown.homeProfileId}" else "placeholder")
                Text(if (open) "folder open" else "folder closed", Modifier.clickable { open = true })
            }
        }
    }

    /** The Activity's widget host, counting how often it is told to let go of its views. */
    private class CountingHost(context: android.content.Context) : LauncherWidgetHost(context, 1) {
        var forgotten = 0
        override fun forgetViews() {
            forgotten++
            super.forgetViews()
        }
    }

    /**
     * Review round 4. The widget views of a profile's home screen are registered with the Activity's host,
     * which outlives that screen. When the screen of a profile leaves, the host lets go of them (they
     * stayed in memory and went on receiving their widgets' updates); a change inside a profile lets go of
     * nothing, or its widgets would be made again for every change.
     */
    @Test fun theHostLetsGoOfAProfilesWidgetViewsWhenItsScreenLeaves() {
        val host = CountingHost(RuntimeEnvironment.getApplication())
        compose.setContent {
            CompositionLocalProvider(LocalAppWidgetHost provides host) {
                HomeOfActiveProfile(active = active.value, uiState = state.value) { _, shown -> Text("home of ${shown.homeProfileId}") }
            }
        }
        compose.onNodeWithText("home of 1").assertIsDisplayed()
        assertThat(host.forgotten).isEqualTo(0)

        state.value = loaded(1).copy(pageCount = 3)
        compose.waitForIdle()
        assertThat(host.forgotten).isEqualTo(0)

        // The switch is known before the new profile's state has come: the old screen has left already.
        active.value = 2
        compose.waitForIdle()
        assertThat(host.forgotten).isEqualTo(1)

        state.value = loaded(2)
        compose.onNodeWithText("home of 2").assertIsDisplayed()
        assertThat(host.forgotten).isEqualTo(1)
    }

    @Test fun whatIsOpenDoesNotSurviveASwitch() {
        show()
        compose.onNodeWithText("folder closed").performClick()
        compose.onNodeWithText("folder open").assertIsDisplayed()

        active.value = 2
        state.value = loaded(2)

        compose.onNodeWithText("home of 2").assertIsDisplayed()
        compose.onNodeWithText("folder closed").assertIsDisplayed()
    }

    @Test fun whatIsOpenStaysThroughAChangeInsideTheProfile() {
        show()
        compose.onNodeWithText("folder closed").performClick()

        state.value = loaded(1).copy(pageCount = 3)

        compose.onNodeWithText("folder open").assertIsDisplayed()
    }

    @Test fun aStateOfTheProfileThatLeftIsNotDrawn() {
        show()

        // The switch is known, and the state is still the one of the profile that left: an old state kept
        // from before the screen went behind Settings, or one the new rows have not reached yet.
        active.value = 2

        compose.onNodeWithText("home of 1").assertDoesNotExist()
        state.value = loaded(2)
        compose.onNodeWithText("home of 2").assertIsDisplayed()
    }

    @Test fun aStateThatIsAheadOfTheNewsOfTheSwitchIsNotDrawn() {
        show()

        state.value = loaded(2)

        compose.onNodeWithText("home of 2").assertDoesNotExist()
        compose.onNodeWithText("home of 1").assertDoesNotExist()
        active.value = 2
        compose.onNodeWithText("home of 2").assertIsDisplayed()
    }

    /** A start does not know its profile at once. Nothing is drawn for a guessed one: the screen of the first
     *  profile, shown for a moment where another is on, would start without what the real one had saved. */
    @Test fun nothingIsDrawnBeforeTheProfileIsKnown() {
        active.value = null
        state.value = HomeUiState()
        show()

        compose.onNodeWithText("placeholder").assertDoesNotExist()
        compose.onNodeWithText("folder closed").assertDoesNotExist()

        active.value = 1
        compose.onNodeWithText("placeholder").assertIsDisplayed()
    }

    /**
     * The process died while a widget was being added in the second profile (its id asked for, the system's
     * question still open) and the screen comes back from its saved state. What the screen starts with is
     * what it saved: a first moment as the first profile's screen saw nothing pending, and the start-up
     * sweep, which keeps only the pending widget's id, freed it under the question.
     */
    @Test fun aRestoredScreenNeverStartsWithoutWhatItsProfileSaved() {
        val startedWith = mutableListOf<String>()
        // What a process knows of the profile that is on: not saved, so a new process starts with [atStart].
        var atStart: Long? = 2
        lateinit var inProcess: MutableState<Long?>
        val tester = StateRestorationTester(compose)
        tester.setContent {
            val known = remember { mutableStateOf(atStart) }.also { inProcess = it }
            val profile = known.value
            HomeOfActiveProfile(active = profile, uiState = if (profile == null) HomeUiState() else loaded(profile)) { _, _ ->
                var pending by rememberSaveable { mutableStateOf("nothing pending") }
                // What a start acts on: the value its first composition has.
                remember { pending.also { startedWith += it } }
                Text(pending, Modifier.clickable { pending = "widget 7 pending" })
            }
        }
        compose.onNodeWithText("nothing pending").performClick()
        compose.onNodeWithText("widget 7 pending").assertIsDisplayed()
        startedWith.clear()

        // The new process: the saved state is there at once, the profile that is on is read a moment later.
        atStart = null
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithText("widget 7 pending").assertDoesNotExist()
        inProcess.value = 2
        compose.waitForIdle()

        compose.onNodeWithText("widget 7 pending").assertIsDisplayed()
        assertThat(startedWith).containsExactly("widget 7 pending")
    }

    @Test fun aStartKeepsWhatItHasWhenTheLayoutArrives() {
        // A start with the second profile on: first nothing is known, then which profile, then its layout.
        active.value = null
        state.value = HomeUiState()
        show()
        compose.onNodeWithText("placeholder").assertDoesNotExist()

        active.value = 2
        compose.onNodeWithText("placeholder").assertIsDisplayed()
        compose.onNodeWithText("folder closed").performClick()
        state.value = loaded(2)

        compose.onNodeWithText("home of 2").assertIsDisplayed()
        compose.onNodeWithText("folder open").assertIsDisplayed()
    }

    @Test fun aLockedGridSwitchIsAButtonAndAnUnlockedPickerDropKeepsItsCell() {
        val drag = HomeDragController()
        val controller = WidgetDragController(drag)
        val locked = mutableStateOf(true)
        var chosen = 0L
        var dropped: Pair<WidgetChoice, WidgetPlacement>? = null
        var removed: Long? = null
        compose.setContent {
            Workspace(
                pageCount = 1,
                columns = 4,
                rows = 6,
                entries = listOf(PlacedBuiltin(11, "profile_switch:2", 0, 0, 0, 1, 1),
                    PlacedBuiltin(12, "unknown", 0, 1, 0, 1, 1)),
                profiles = listOf(HomeProfile(2, "Work")),
                onSwitchProfile = { chosen = it },
                badges = emptyMap(),
                badgeShowCount = false,
                badgeScale = 1f,
                showLabels = true,
                showPageIndicator = true,
                locked = locked.value,
                homeSignals = emptyFlow(),
                homePage = 0,
                dragController = drag,
                widgetDragController = controller,
                onAddWidget = { choice, placement -> dropped = choice to placement },
                onRemoveWidget = { id, _ -> removed = id },
                onAppClick = {},
                onAppMenu = { _, _, _ -> },
                onMove = { _, _, _, _ -> true },
                onMoveFolder = { _, _, _, _ -> true },
                onDropOnDock = { _, _ -> },
                onRemoveFromHome = {},
                onOpenFolder = { _, _ -> },
                onLaunchShortcut = {},
                onRemoveShortcut = {},
                onCreateFolder = { _, _ -> },
                onAddToFolder = { _, _ -> },
                onEmptyAreaMenu = { _, _ -> },
                modifier = Modifier.size(300.dp, 500.dp),
            )
        }
        compose.onNodeWithContentDescription("Switch to profile Work")
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performTouchInput { click() }
        assertThat(chosen).isEqualTo(2L)
        compose.onNodeWithText("Widget unavailable").assertIsDisplayed()
        locked.value = false
        val placeholder = compose.onNodeWithText("Widget unavailable")
        val destination = placeholder.fetchSemanticsNode().boundsInRoot.center + Offset(40f, 40f)
        compose.runOnIdle { drag.removeBounds = Rect(destination, 12f) }
        placeholder.performTouchInput {
            down(center)
            advanceEventTime(1_000)
            moveBy(Offset(40f, 40f))
            up()
        }
        assertThat(removed).isEqualTo(12L)
        val spot = WidgetPlacement(0, 2, 3, 1, 1)
        val choice = WidgetChoice.Builtin("profile_switch")
        compose.runOnIdle {
            controller.onDrop!!.invoke(WidgetDrag(null, choice, 1, 1, Offset.Zero, null), spot)
        }
        assertThat(dropped).isEqualTo(choice to spot)
    }

    @Test fun aSwitchLandsOnTheNewProfilesStartPage() {
        val drag = HomeDragController()
        val pageRequests = MutableSharedFlow<Int>(extraBufferCapacity = 1)
        state.value = loaded(1).copy(pageCount = 4, homePage = 0)
        compose.setContent {
            HomeOfActiveProfile(active = active.value, uiState = state.value) { _, shown ->
                Workspace(
                    pageCount = shown.pageCount,
                    columns = 4,
                    rows = 6,
                    entries = emptyList(),
                    badges = emptyMap(),
                    badgeShowCount = false,
                    badgeScale = 1f,
                    showLabels = true,
                    showPageIndicator = true,
                    locked = false,
                    homeSignals = emptyFlow(),
                    homePage = shown.homePage,
                    pageRequests = pageRequests,
                    dragController = drag,
                    widgetDragController = remember { WidgetDragController(drag) },
                    onAddWidget = { _, _ -> },
                    onAppClick = {},
                    onAppMenu = { _, _, _ -> },
                    onMove = { _, _, _, _ -> true },
                    onMoveFolder = { _, _, _, _ -> true },
                    onDropOnDock = { _, _ -> },
                    onRemoveFromHome = {},
                    onOpenFolder = { _, _ -> },
                    onLaunchShortcut = {},
                    onRemoveShortcut = {},
                    onCreateFolder = { _, _ -> },
                    onAddToFolder = { _, _ -> },
                    onEmptyAreaMenu = { _, _ -> },
                    modifier = Modifier.size(300.dp, 500.dp),
                )
            }
        }
        // The user is on the fourth page of the first profile.
        compose.runOnIdle { pageRequests.tryEmit(3) }
        compose.waitForIdle()
        assertThat(drag.currentPage).isEqualTo(3)

        active.value = 2
        state.value = loaded(2).copy(pageCount = 4, homePage = 1)
        compose.waitForIdle()

        assertThat(drag.currentPage).isEqualTo(1)
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun homePassesAutomaticInkToItsPageDots() {
        val context: Application = RuntimeEnvironment.getApplication()
        val wiring = ProductionWiring(context, TestScope(), inMemorySettings = true)
        val vm = whileTheMainLooperTurns { wiring.homeViewModel() }
        val glow = ChargeGlowViewModel(BatteryMonitor(context))
        val present = mutableStateOf(true)
        val look = mutableStateOf(WallpaperLook(true, -1))
        fun drawnDot(): Color {
            val dot = compose.onAllNodesWithTag(PAGE_DOT_TAG, useUnmergedTree = true)[0].fetchSemanticsNode().boundsInWindow.center
            val window = (compose as AndroidComposeTestRule<*, *>).activity.window.decorView
            val image = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)
            compose.runOnUiThread { window.draw(Canvas(image)) }
            return Color(image.getPixel(dot.x.roundToInt(), dot.y.roundToInt()))
        }
        try {
            compose.setContent {
                val settings = LauncherSettings(automaticHomeLabels = true, homePageMarker = LauncherSettings.MARKER_RING,
                    defaultReminderDismissed = true)
                if (present.value) HomeScreen(loaded(1).copy(settings = settings, pageCount = 2), wallpaper = look.value,
                    onOpenDrawer = {}, onOpenSettings = {}, onManageProfiles = {}, viewModel = vm, chargeGlow = glow,
                    modifier = Modifier.size(350.dp, 600.dp).background(Color.Gray))
            }
            assertThat(drawnDot()).isEqualTo(Color.Black)
            compose.runOnIdle { look.value = WallpaperLook(false, -1) }
            assertThat(drawnDot()).isEqualTo(Color.White)
        } finally {
            present.value = false
            compose.waitForIdle()
            wiring.clear(vm)
            wiring.close()
        }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun gridDockAndFolderLabelsFollowTheWallpaperAndSwitchWithTheirProfile() {
        val context: Application = RuntimeEnvironment.getApplication()
        val wiring = ProductionWiring(context, TestScope(), inMemorySettings = true)
        val vm = whileTheMainLooperTurns { wiring.homeViewModel() }
        val glow = ChargeGlowViewModel(BatteryMonitor(context))
        val app = AppItem("test.app", "Main", android.os.Process.myUserHandle(), 0, "Application with a long name that needs two lines on home")
        val settings = LauncherSettings(automaticHomeLabels = true, appLabelTextScale = 0.8f,
            twoLineHomeLabels = true, showDockLabels = true, defaultReminderDismissed = true, dockBackgroundOpacity = 0.8f)
        val frame = mutableStateOf(loaded(1).copy(settings = settings,
            entries = listOf(PlacedApp(app, 0, 0, 0), PlacedFolder(10, "Grid folder", emptyList(), 0, 1, 0)),
            dockEntries = listOf(DockApp(21, app.copy(label = "Dock app")),
                DockFolder(PlacedFolder(22, "Dock folder", emptyList(), DOCK_PAGE, 1, 0)))))
        val look = mutableStateOf(WallpaperLook(true, -1))
        val present = mutableStateOf(true)
        fun label(name: String): TextLayoutResult {
            val node = compose.onNodeWithText(name, useUnmergedTree = true).fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            return layouts.single()
        }
        fun checkInk(name: String, expected: Color, automatic: Boolean) {
            val style = label(name).layoutInput.style
            assertThat(style.color).isEqualTo(expected)
            if (automatic) {
                val shadow = checkNotNull(style.shadow)
                assertThat(shadow.color.copy(alpha = 1f)).isEqualTo(if (expected == Color.Black) Color.White else Color.Black)
                assertThat(shadow.blurRadius).isGreaterThan(0f)
            } else assertThat(style.shadow).isNull()
        }
        try {
            compose.setContent {
                if (present.value) HomeScreen(frame.value, wallpaper = look.value, onOpenDrawer = {}, onOpenSettings = {},
                    onManageProfiles = {}, modifier = Modifier.size(350.dp, 600.dp), viewModel = vm, chargeGlow = glow)
            }
            checkInk(app.label, Color.Black, true)
            assertThat(label(app.label).lineCount).isEqualTo(2)
            checkInk("Grid folder", Color.Black, true)
            checkInk("Dock app", Color.White, true)
            checkInk("Dock folder", Color.White, true)
            look.value = WallpaperLook(false, 0xfff5f5f5.toInt())
            frame.value = frame.value.copy(settings = settings.copy(dockBackgroundOpacity = 0.35f))
            checkInk(app.label, Color.White, true)
            checkInk("Dock app", Color.White, true)
            val red = 0xffef5350.toInt()
            frame.value = frame.value.copy(homeProfileId = 2, settings = settings.copy(homeProfileId = 2,
                automaticHomeLabels = false, appLabelColor = red))
            for (name in listOf(app.label, "Grid folder", "Dock app", "Dock folder")) checkInk(name, Color(red), false)
        } finally {
            present.value = false
            compose.waitForIdle()
            wiring.clear(vm)
            wiring.close()
        }
    }

}
