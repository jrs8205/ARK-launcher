package org.arkikeskus.launcher.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PeopleGroupingTest {

    private fun entry(name: String, time: Long, key: String = "$name/$time", count: Int = 1) = PersonEntry(
        key = key, name = name, text = "hi", postTime = time, packageName = "app", userSerial = 0,
        kind = PersonEventKind.MESSAGE, count = count,
    )

    @Test
    fun `same person across apps merges into one tile, newest first`() {
        val tiles = PeopleGrouping.group(
            listOf(
                entry("Mikko", 10).copy(packageName = "sms"),
                entry("mikko", 30).copy(packageName = "whatsapp"),
                entry("Mikko ", 20).copy(packageName = "signal"),
            ),
        )
        assertThat(tiles).hasSize(1)
        assertThat(tiles[0].name).isEqualTo("mikko")
        assertThat(tiles[0].entries.map { it.postTime }).containsExactly(30L, 20L, 10L).inOrder()
        assertThat(tiles[0].keys).hasSize(3)
    }

    @Test
    fun `tiles sort by their newest notification`() {
        val tiles = PeopleGrouping.group(listOf(entry("Anna", 5), entry("Bob", 50), entry("Anna", 40)))
        assertThat(tiles.map { it.name }).containsExactly("Bob", "Anna").inOrder()
    }

    @Test
    fun `count sums the messages of every notification`() {
        val tiles = PeopleGrouping.group(listOf(entry("Anna", 1, count = 3), entry("Anna", 2, count = 2)))
        assertThat(tiles.single().count).isEqualTo(5)
    }

    @Test
    fun `aliases merge tiles and the target keeps its own name`() {
        val tiles = PeopleGrouping.group(listOf(entry("Mikko", 1), entry("M. Mäkelä", 5)))
        assertThat(tiles).hasSize(2)
        val merged = PeopleGrouping.merge(tiles, mapOf("m. mäkelä" to "mikko"))
        assertThat(merged).hasSize(1)
        assertThat(merged[0].personKey).isEqualTo("mikko")
        assertThat(merged[0].name).isEqualTo("Mikko")
        assertThat(merged[0].entries.map { it.postTime }).containsExactly(5L, 1L).inOrder()
    }

    @Test
    fun `held state needs every entry to be waiting`() {
        val waiting = PeopleGrouping.group(listOf(entry("Anna", 1).copy(heldUntil = 99), entry("Anna", 2).copy(heldUntil = 120)))
        assertThat(waiting.single().held).isTrue()
        assertThat(waiting.single().heldUntil).isEqualTo(120)
        val mixed = PeopleGrouping.group(listOf(entry("Anna", 1).copy(heldUntil = 99), entry("Anna", 2)))
        assertThat(mixed.single().held).isFalse()
    }

    @Test
    fun `blank names are dropped and inner whitespace is collapsed`() {
        val tiles = PeopleGrouping.group(listOf(entry("  ", 1), entry("Mikko  Mäkelä", 2), entry("mikko mäkelä", 3)))
        assertThat(tiles).hasSize(1)
        assertThat(tiles[0].personKey).isEqualTo("mikko mäkelä")
    }
}
