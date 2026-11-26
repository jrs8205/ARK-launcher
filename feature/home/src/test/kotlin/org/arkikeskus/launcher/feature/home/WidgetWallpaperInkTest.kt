package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.test.TestScope
import org.arkikeskus.launcher.data.NotificationBadgeRepository
import org.arkikeskus.launcher.data.PeopleGrouping
import org.arkikeskus.launcher.data.PersonEntry
import org.arkikeskus.launcher.data.PersonEventKind
import org.arkikeskus.launcher.data.PinnedPerson
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.data.search.ContactDataSource
import org.arkikeskus.launcher.data.search.RawContact
import org.arkikeskus.launcher.designsystem.theme.LauncherTheme
import org.arkikeskus.launcher.launcher.system.BatteryMonitor
import org.arkikeskus.launcher.model.LauncherSettings
import org.arkikeskus.launcher.model.WallpaperLook
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en", shadows = [WidgetWallpaperInkTest.WidgetFactory::class])
class WidgetWallpaperInkTest {
    /** The host activity uses the same widget entry points with a factory backed by the test stores. */
    @Implements(dagger.hilt.android.internal.lifecycle.HiltViewModelFactory::class, isInAndroidSdk = false)
    class WidgetFactory {
        companion object {
            @JvmStatic @Implementation
            fun createInternal(activity: android.app.Activity, delegate: ViewModelProvider.Factory) = delegate
        }
    }

    @get:Rule val compose = createComposeRule()

    private fun windowBitmap(): Bitmap {
        val window = (compose as AndroidComposeTestRule<*, *>).activity.window.decorView
        val image = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { window.draw(Canvas(image)) }
        return image
    }

    private fun matchingPixels(image: Bitmap, node: SemanticsNode, matches: (Int) -> Boolean): Int {
        val rect = node.boundsInWindow
        var count = 0
        for (y in rect.top.roundToInt() until rect.bottom.roundToInt()) {
            for (x in rect.left.roundToInt() until rect.right.roundToInt()) {
                if (matches(image.getPixel(x, y))) count++
            }
        }
        return count
    }

    private fun showUnreadTile(ageMinutes: Int, container: Color, onContainer: Color): (Int) -> Unit {
        compose.mainClock.autoAdvance = false
        val entry = PersonEntry("message", "Sender", text = "Hello", postTime = 1000, packageName = "app",
            userSerial = 0, kind = PersonEventKind.MESSAGE)
        val tile = PeopleWidgetViewModel.Tile("sender", "Sender", null, PeopleGrouping.group(listOf(entry)).single(), null)
        val age = mutableStateOf(ageMinutes)
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(primaryContainer = container, onPrimaryContainer = onContainer)) {
                CompositionLocalProvider(LocalAutomaticWidgetWallpaper provides WallpaperLook(false, 0xfff5f5f5.toInt())) {
                    WidgetOverWallpaper(Modifier.size(220.dp, 120.dp).background(Color.Black)) {
                        PersonTile(tile, LauncherSettings.PRIVACY_ALL, entry.postTime + age.value * 60_000L, wide = true, onOpen = {},
                            onDismiss = {}, onLongPress = {}, interaction = remember { MutableInteractionSource() }, modifier = it)
                    }
                }
            }
        }
        return { minutes ->
            compose.runOnIdle { age.value = minutes }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }

    private fun namePixels(): IntArray {
        compose.waitForIdle()
        val bounds = compose.onNodeWithText("Sender", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        val width = bounds.width.roundToInt()
        val height = bounds.height.roundToInt()
        return IntArray(width * height).also {
            windowBitmap().getPixels(it, 0, width, bounds.left.roundToInt(), bounds.top.roundToInt(), width, height)
        }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun aPeopleTileUsesDarkTextAtTwentyMinutesAndLightTextAtThreeHoursWithoutTheHint() {
        val setAge = showUnreadTile(20, container = Color(0xfffba083), onContainer = Color.Black)
        assertWithMessage("dark name pixels at 20 minutes").that(namePixels().count { it == Color.Black.toArgb() })
            .isGreaterThan(5)
        setAge(180)
        compose.mainClock.advanceTimeBy(1000)
        assertWithMessage("light name pixels at 3 hours").that(namePixels().count { it == Color.White.toArgb() })
            .isGreaterThan(5)
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun aPeopleTileFadesItsTextWhenAFreshMessageAges() {
        val setAge = showUnreadTile(0, container = Color.Blue, onContainer = Color.Red)
        // Track a fully covered glyph pixel. Its green channel moves from 0 (red) to 255 (white).
        val glyph = namePixels().indexOf(Color.Red.toArgb())
        assertWithMessage("a solid red pixel in the fresh name").that(glyph).isAtLeast(0)
        setAge(180)
        compose.mainClock.advanceTimeBy(48)
        val green = android.graphics.Color.green(namePixels()[glyph])
        assertWithMessage("name pixel between red and white at 48 ms").that(green).isIn(1..254)
        compose.mainClock.advanceTimeBy(1000)
        assertThat(namePixels()[glyph]).isEqualTo(Color.White.toArgb())
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun batteryNotificationsAndPeopleUseTheVeilOnlyWithAutomatic() {
        compose.mainClock.autoAdvance = false
        val context: Application = RuntimeEnvironment.getApplication()
        val wiring = ProductionWiring(context, TestScope(), inMemorySettings = true)
        val badges = NotificationBadgeRepository()
        val battery = BatteryWidgetViewModel(BatteryMonitor(context))
        val notifications = NotificationsWidgetViewModel(context, badges, wiring.appRepository, wiring.settingsRepository)
        val people = PeopleWidgetViewModel(context, badges, wiring.settingsRepository, wiring.appRepository,
            object : ContactDataSource { override suspend fun search(query: String, limit: Int) = emptyList<RawContact>() },
            { false })
        val vm = whileTheMainLooperTurns {
            wiring.settingsRepository.pinPerson(PinnedPerson("test-person", "Pinned person"))
            wiring.homeViewModel()
        }
        val glow = ChargeGlowViewModel(BatteryMonitor(context))
        val owner = object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore = ViewModelStore()
            override val defaultViewModelProviderFactory = viewModelFactory {
                initializer { battery }
                initializer { notifications }
                initializer { people }
            }
        }
        val wallpaper = mutableStateOf(WallpaperLook(true, -1))
        val automatic = mutableStateOf(true)
        val widgets = listOf(HomeItemEntity.BUILTIN_BATTERY, HomeItemEntity.BUILTIN_NOTIFICATIONS, HomeItemEntity.BUILTIN_PEOPLE)
            .mapIndexed { i, type -> PlacedBuiltin(i + 1L, type, 0, 0, i, 4, 1) }
        val tonal = mutableStateOf(false)
        val present = mutableStateOf(true)
        var themedInk = Color.Unspecified
        var themedSurface = Color.Unspecified
        fun styles(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().map { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            layouts.single().layoutInput.style
        }
        fun colors(text: String) = styles(text).map { it.color.copy(alpha = 1f) }
        val hint = context.getString(R.string.notifications_widget_allow_access)
        // A colour-only redraw need not replace TextLayoutResult. Read the actual ink after a change.
        fun checkWidgets(color: Color) {
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            val image = windowBitmap()
            val surface = if (tonal.value) themedSurface else Color.Black.copy(alpha = 0.3f).compositeOver(Color.Gray)
            for ((label, count) in listOf("57%" to 1, hint to 2)) {
                val expected = if (label == hint) color.copy(alpha = 0.85f).compositeOver(surface).toArgb() else color.toArgb()
                val nodes = compose.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes()
                assertThat(nodes).hasSize(count)
                for (node in nodes) {
                    val matches = matchingPixels(image, node) { pixel ->
                        listOf(0, 8, 16).all { shift ->
                            abs(((pixel ushr shift) and 255) - ((expected ushr shift) and 255)) <= 50
                        }
                    }
                    com.google.common.truth.Truth.assertWithMessage("$label drawn in $color").that(matches).isGreaterThan(10)
                }
            }
        }
        try {
            @Suppress("DEPRECATION")
            context.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 57).putExtra(BatteryManager.EXTRA_SCALE, 100))
            compose.setContent {
                LauncherTheme {
                    themedInk = MaterialTheme.colorScheme.onSurface
                    themedSurface = MaterialTheme.colorScheme.surfaceContainer
                    if (present.value) CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        val settings = LauncherSettings(automaticHomeLabels = automatic.value, widgetTonalBackground = tonal.value,
                            homeRows = 4, defaultReminderDismissed = true)
                        HomeScreen(HomeUiState(settings = settings, loaded = true, homeProfileId = 1, entries = widgets),
                            wallpaper = wallpaper.value, onOpenDrawer = {}, onOpenSettings = {}, onManageProfiles = {},
                            modifier = Modifier.size(350.dp, 600.dp).background(Color.Gray), viewModel = vm, chargeGlow = glow)
                    }
                }
            }
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                colors("57%").isNotEmpty()
            }
            assertThat(colors("57%")).containsExactly(Color.Black)
            assertThat(colors(hint)).containsExactly(Color.Black, Color.Black)
            assertThat(styles("57%").single().shadow).isNull()
            assertThat(styles(hint).map { it.shadow }).containsExactly(null, null)
            checkWidgets(Color.Black)

            compose.runOnIdle { wallpaper.value = WallpaperLook(false, 0xfff5f5f5.toInt()) }
            checkWidgets(Color.White)
            compose.runOnIdle { people.hasAccess.value = true }
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                colors("Pinned person").isNotEmpty()
            }
            assertThat(colors("Pinned person")).containsExactly(Color.White)
            compose.runOnIdle {
                people.hasAccess.value = false
                wallpaper.value = WallpaperLook(true, -1)
                automatic.value = false
            }
            checkWidgets(Color.White)
            compose.runOnIdle { automatic.value = true; tonal.value = true }
            checkWidgets(themedInk)
        } finally {
            present.value = false
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            owner.viewModelStore.clear()
            wiring.clear(vm)
            wiring.close()
        }
    }
}
