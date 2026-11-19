package org.arkikeskus.launcher.data.profile

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.HomeFolderIcons
import org.arkikeskus.launcher.data.HomeLayout
import org.arkikeskus.launcher.data.local.HomeItemEntity
import org.arkikeskus.launcher.model.CustomIcon
import org.arkikeskus.launcher.model.LauncherSettings
import org.junit.Test

/**
 * A switch of the home profile reaches a screen as one change. The settings, the layout rows and the folders'
 * own icons are three stores that each learn of the switch at their own moment; a screen must never be handed
 * the rows of one profile with the settings or the icons of another.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeProfileContentTest {
    private fun settings(profile: Long, columns: Int = 4) = LauncherSettings(homeProfileId = profile, homeColumns = columns)

    private fun layout(profile: Long, vararg packages: String) = HomeLayout(
        profile,
        packages.mapIndexed { i, pkg -> HomeItemEntity(packageName = pkg, className = "Main", page = 0, cellX = i, cellY = 0) },
    )

    private fun icons(profile: Long, vararg keys: String) =
        HomeFolderIcons(profile, keys.associateWith { CustomIcon.FromPack("com.pack", it) })

    private val settingsFlow = MutableStateFlow(settings(1))
    private val layoutFlow = MutableStateFlow(layout(1, "com.mail"))
    private val iconsFlow = MutableStateFlow(icons(1, "home:7"))

    /** Everything a screen is handed from here on. */
    private fun TestScope.handedOver(): List<HomeProfileContent> {
        val seen = ArrayList<HomeProfileContent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            homeProfileContent(settingsFlow, layoutFlow, iconsFlow).collect { seen += it }
        }
        return seen
    }

    @Test fun aSwitchArrivesAsOneChangeWhicheverStoreLearnsOfItFirst() = runTest {
        val toSecond = listOf<() -> Unit>(
            { settingsFlow.value = settings(2, columns = 5) },
            { layoutFlow.value = layout(2, "com.maps") },
            { iconsFlow.value = icons(2, "home:9") },
        )
        val orders = listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))
        for (order in orders) {
            settingsFlow.value = settings(1)
            layoutFlow.value = layout(1, "com.mail")
            iconsFlow.value = icons(1, "home:7")
            val seen = handedOver()

            order.forEach { toSecond[it]() }

            assertWithMessage("order $order").that(seen).containsExactly(
                HomeProfileContent(settings(1), layout(1, "com.mail"), icons(1, "home:7").icons),
                HomeProfileContent(settings(2, columns = 5), layout(2, "com.maps"), icons(2, "home:9").icons),
            ).inOrder()
        }
    }

    @Test fun aChangeInsideTheProfileIsHandedOverAtOnce() = runTest {
        val seen = handedOver()

        layoutFlow.value = layout(1, "com.mail", "com.maps")
        settingsFlow.value = settings(1, columns = 6)
        iconsFlow.value = icons(1, "home:7", "home:8")

        assertThat(seen.map { it.layout.items.size }).containsExactly(1, 2, 2, 2).inOrder()
        assertThat(seen.last().settings.homeColumns).isEqualTo(6)
        assertThat(seen.last().folderIcons.keys).containsExactly("home:7", "home:8")
    }

    @Test fun anEmptyProfileIsStillHandedOver() = runTest {
        val seen = handedOver()

        settingsFlow.value = settings(2)
        iconsFlow.value = icons(2)
        layoutFlow.value = layout(2)

        assertThat(seen.last().homeProfileId).isEqualTo(2L)
        assertThat(seen.last().layout.items).isEmpty()
    }
}
