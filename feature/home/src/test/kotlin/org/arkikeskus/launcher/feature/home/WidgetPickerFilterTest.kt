package org.arkikeskus.launcher.feature.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Which groups the widget picker lists: the search narrows them, an app's Widgets row pins them to that app. */
class WidgetPickerFilterTest {
    private fun option(label: String, type: String = label.lowercase()) = WidgetOption(WidgetChoice.Builtin(type), label)

    private val builtin = WidgetGroup("builtin", "ARK-Launcher", listOf(option("Clock"), option("Battery")))
    private val chat = WidgetGroup("com.chat", "Chat", listOf(option("Conversations"), option("Calls")))
    private val maps = WidgetGroup("com.maps", "Maps", listOf(option("Directions")))
    private val all = listOf(builtin, chat, maps)

    @Test fun withoutASearchEveryGroupIsListed() {
        assertThat(visibleWidgetGroups(all, query = "", onlyPackage = null)).containsExactly(builtin, chat, maps).inOrder()
    }

    @Test fun theSearchMatchesAGroupLabelWholeOrSingleWidgets() {
        assertThat(visibleWidgetGroups(all, query = "chat", onlyPackage = null)).containsExactly(chat)

        val narrowed = visibleWidgetGroups(all, query = "cal", onlyPackage = null)
        assertThat(narrowed.map { it.packageName }).containsExactly("com.chat")
        assertThat(narrowed.single().widgets.map { it.label }).containsExactly("Calls")
    }

    @Test fun anAppsWidgetsRowListsOnlyThatApp() {
        assertThat(visibleWidgetGroups(all, query = "", onlyPackage = "com.maps")).containsExactly(maps)
    }

    @Test fun theAppFilterIgnoresTheSearchAndTheBuiltins() {
        assertThat(visibleWidgetGroups(all, query = "clock", onlyPackage = "com.chat")).containsExactly(chat)
        assertThat(visibleWidgetGroups(all, query = "", onlyPackage = "com.none")).isEmpty()
    }
}
