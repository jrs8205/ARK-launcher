package org.arkikeskus.launcher.feature.home

import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.ui.AppShortcuts
import org.junit.Test

/** The resolved label/icon/state of the shortcuts pinned on home, and when the home state re-resolves them. */
class PinnedShortcutCacheTest {
    private val cache = PinnedShortcutCache()

    private fun key(pkg: String) = PinnedShortcutCache.Key(pkg, "s", 0L)

    private fun key(pkg: String, shape: org.arkikeskus.launcher.model.IconShape) = PinnedShortcutCache.Key(pkg, "s", 0L, shape)

    /** A shortcut's picture is drawn in the icon shape, so one resolved for another shape is another entry. */
    @org.junit.Test fun aShortcutResolvedForOneShapeIsNotServedForAnother() {
        val cache = PinnedShortcutCache()
        val round = org.arkikeskus.launcher.ui.AppShortcuts.Resolved("Mum", null, enabled = true, disabledMessage = null)
        cache.put(key("com.contacts", org.arkikeskus.launcher.model.IconShape.CIRCLE), round, cache.epoch("com.contacts"))

        com.google.common.truth.Truth.assertThat(cache[key("com.contacts", org.arkikeskus.launcher.model.IconShape.CIRCLE)]).isSameInstanceAs(round)
        com.google.common.truth.Truth.assertThat(cache[key("com.contacts", org.arkikeskus.launcher.model.IconShape.HEXAGON)]).isNull()
        com.google.common.truth.Truth.assertThat(cache[key("com.contacts")]).isNull()
    }

    /** An app's change drops its shortcuts in every shape they were resolved for. */
    @org.junit.Test fun anAppsChangeDropsItsShortcutsInEveryShape() {
        val cache = PinnedShortcutCache()
        val resolved = org.arkikeskus.launcher.ui.AppShortcuts.Resolved("Mum", null, enabled = true, disabledMessage = null)
        for (shape in listOf(org.arkikeskus.launcher.model.IconShape.SYSTEM, org.arkikeskus.launcher.model.IconShape.CIRCLE)) {
            cache.put(key("com.contacts", shape), resolved, cache.epoch("com.contacts"))
        }

        cache.invalidate("com.contacts")

        com.google.common.truth.Truth.assertThat(cache[key("com.contacts", org.arkikeskus.launcher.model.IconShape.SYSTEM)]).isNull()
        com.google.common.truth.Truth.assertThat(cache[key("com.contacts", org.arkikeskus.launcher.model.IconShape.CIRCLE)]).isNull()
    }
    private fun resolved(label: String) = AppShortcuts.Resolved(label, icon = null, enabled = true, disabledMessage = null)

    private val chat = "com.chat" to 0L
    private val other = "com.other" to 0L

    @Test fun aPackageEventDropsOnlyThatAppsEntriesAndRecomputesHome() {
        cache.put(key("com.chat"), resolved("Mia"), cache.epoch("com.chat"))
        cache.put(key("com.other"), resolved("Other"), cache.epoch("com.other"))
        val before = cache.generation.value

        cache.invalidate("com.chat")

        assertThat(cache[key("com.chat")]).isNull()
        assertThat(cache[key("com.other")]?.label).isEqualTo("Other")
        assertThat(cache.generation.value).isGreaterThan(before)
    }

    @Test fun aPackageEventForAnAppWithNothingCachedDoesNotRecomputeHome() {
        val before = cache.generation.value

        cache.invalidate("com.chat")

        assertThat(cache.generation.value).isEqualTo(before)
    }

    @Test fun aShortcutChangeDropsTheEntriesOfTheAppsOnHomeAndNamesThem() {
        cache.put(key("com.chat"), resolved("Mia"), cache.epoch("com.chat"))
        cache.put(key("com.other"), resolved("Other"), cache.epoch("com.other"))
        val before = cache.generation.value

        val onHome = cache.shortcutsChanged(setOf(chat, "com.news" to 0L), pinned = setOf(chat, other))

        assertThat(onHome).containsExactly(chat)
        assertThat(cache[key("com.chat")]).isNull()
        assertThat(cache[key("com.other")]?.label).isEqualTo("Other")
        assertThat(cache.generation.value).isGreaterThan(before)
    }

    @Test fun aShortcutChangeOfAnAppOnHomeWithNothingCachedStillRecomputesHome() {
        // A row whose last resolution failed has no entry: only a new pass can bring its icon back.
        val before = cache.generation.value

        cache.shortcutsChanged(setOf(chat), pinned = setOf(chat))

        assertThat(cache.generation.value).isGreaterThan(before)
    }

    @Test fun aShortcutChangeOfAnAppWithNothingOnHomeIsIgnored() {
        val before = cache.generation.value

        val onHome = cache.shortcutsChanged(setOf(chat), pinned = setOf(other))

        assertThat(onHome).isEmpty()
        assertThat(cache.generation.value).isEqualTo(before)
    }

    @Test fun aShortcutChangeInAnotherProfileIsAnotherOwner() {
        val workChat = "com.chat" to 10L
        val before = cache.generation.value

        val onHome = cache.shortcutsChanged(setOf(workChat), pinned = setOf(chat))

        assertThat(onHome).isEmpty()
        assertThat(cache.generation.value).isEqualTo(before)
    }

    @Test fun aResolutionThatRacedAPackageEventIsDroppedAndRetried() {
        val startedAt = cache.epoch("com.chat")
        cache.invalidate("com.chat")
        val before = cache.generation.value

        cache.put(key("com.chat"), resolved("Old label"), startedAt)

        assertThat(cache[key("com.chat")]).isNull()
        assertThat(cache.generation.value).isGreaterThan(before)
    }

    @Test fun aResolutionThatRacedAShortcutChangeIsDroppedAndRetried() {
        val startedAt = cache.epoch("com.chat")
        cache.shortcutsChanged(setOf(chat), pinned = setOf(chat))
        val before = cache.generation.value

        cache.put(key("com.chat"), resolved("Old label"), startedAt)

        assertThat(cache[key("com.chat")]).isNull()
        assertThat(cache.generation.value).isGreaterThan(before)
    }
}
