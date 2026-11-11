package org.arkikeskus.launcher.feature.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Finding a setting by what is written on it: the words of the query, in any order, each somewhere in the row's
 * title, description or extra words, whatever the case and the accents. Rows whose title holds the first word come
 * first; otherwise the page order is kept.
 */
class SettingsSearchTest {
    private fun setting(
        id: String,
        title: String,
        description: String? = null,
        category: SettingsCategory? = SettingsCategory.DRAWER,
        keywords: List<String> = emptyList(),
    ) = SearchableSetting(id, category, title, description, keywords)

    private fun ids(query: String, vararg settings: SearchableSetting) = searchSettings(query, settings.toList()).map { it.setting.id }

    @Test fun aPartOfATitleFindsTheRowAndSaysWhereItMatched() {
        val blur = setting("A14", "Sumenna taustakuva sovellusvalikon takana")
        val strength = setting("A15", "Sumennuksen voimakkuus")
        val other = setting("A16", "Sovellusvalikon kuvakekoko")

        val hits = searchSettings("sume", listOf(blur, strength, other))

        assertThat(hits.map { it.setting.id }).containsExactly("A14", "A15").inOrder()
        assertThat(hits.map { it.titleMatch }).containsExactly(0..3, 0..3).inOrder()
    }

    @Test fun caseAndAccentsDoNotMatter() {
        val row = setting("H3", "Näytä nimet")
        assertThat(ids("nayta", row)).containsExactly("H3")
        assertThat(ids("NÄYTÄ", row)).containsExactly("H3")
        assertThat(ids("Näytä", setting("X", "nayta nimet"))).containsExactly("X")
    }

    @Test fun theMatchIsReportedInTheTitleAsItIsWritten() {
        val hit = searchSettings("nimet", listOf(setting("H3", "Näytä nimet"))).single()
        assertThat(hit.titleMatch).isEqualTo(6..10)
        assertThat("Näytä nimet".substring(hit.titleMatch!!)).isEqualTo("nimet")
    }

    @Test fun theSameTitleOnThreePagesIsThreeHitsToldApartByTheirPage() {
        val hits = searchSettings(
            "nayta nimet",
            listOf(
                setting("H3", "Näytä nimet", category = SettingsCategory.HOME),
                setting("D3", "Näytä nimet", category = SettingsCategory.DOCK),
                setting("A2", "Näytä nimet", category = SettingsCategory.DRAWER),
            ),
        )
        assertThat(hits.map { it.setting.category }).containsExactly(
            SettingsCategory.HOME, SettingsCategory.DOCK, SettingsCategory.DRAWER,
        ).inOrder()
    }

    @Test fun turkishDotlessAndDottedIFold() {
        assertThat(ids("ISIK", setting("T", "Işık ayarı"))).containsExactly("T")
        assertThat(ids("isik", setting("T", "IŞIK"))).containsExactly("T")
        assertThat(ids("istanbul", setting("T", "İstanbul"))).containsExactly("T")
    }

    @Test fun cyrillicFoldsCaseAndTheLetterYo() {
        assertThat(ids("ярлык", setting("R", "Ярлыки приложений"))).containsExactly("R")
        assertThat(ids("елка", setting("R", "Ёлка"))).containsExactly("R")
    }

    @Test fun polishLStrokeFolds() {
        assertThat(ids("tlo", setting("P", "Tło"))).containsExactly("P")
    }

    @Test fun everyWordMustBeFoundSomewhereOnTheRow() {
        val both = setting("A15", "Blur strength")
        val split = setting("A14", "Blur behind the drawer", description = "Its strength is the row below")
        val onlyOne = setting("A1", "Blur")
        assertThat(ids("blur strength", both, split, onlyOne)).containsExactly("A15", "A14")
        assertThat(ids("strength blur", both, split, onlyOne)).containsExactly("A15", "A14")
    }

    @Test fun rowsWhoseTitleHoldsTheFirstWordComeFirst() {
        val inDescription = setting("H7", "Glass layer", description = "Under the icons and the dock")
        val inTitle = setting("D1", "Show dock")
        assertThat(ids("dock", inDescription, inTitle)).containsExactly("D1", "H7").inOrder()
        assertThat(searchSettings("dock", listOf(inDescription, inTitle)).last().titleMatch).isNull()
    }

    @Test fun otherwiseTheRowsKeepTheirOrder() {
        val a = setting("A", "Dock icons")
        val b = setting("B", "Dock background")
        val c = setting("C", "Dock spacing")
        assertThat(ids("dock", a, b, c)).containsExactly("A", "B", "C").inOrder()
    }

    @Test fun anExtraWordFindsTheRowWithoutMarkingItsTitle() {
        val support = setting("E1", "Support ARK-launcher", category = null, keywords = listOf("GitHub Sponsors", "PayPal"))
        val hit = searchSettings("paypal", listOf(support)).single()
        assertThat(hit.setting.id).isEqualTo("E1")
        assertThat(hit.titleMatch).isNull()
    }

    @Test fun nothingTypedFindsNothing() {
        val row = setting("A", "Anything")
        assertThat(ids("", row)).isEmpty()
        assertThat(ids("   ", row)).isEmpty()
        assertThat(ids("\n\t", row)).isEmpty()
    }

    @Test fun oddInputFindsNothingAndDoesNotFail() {
        val row = setting("A", "Anything (really)")
        assertThat(ids("a".repeat(500), row)).isEmpty()
        assertThat(ids("( * [ \\", row)).isEmpty()
        assertThat(ids("(really)", row)).containsExactly("A")
        assertThat(ids(".*", row)).isEmpty()
    }

    @Test fun foldingLeavesPlainTextAlone() {
        assertThat(foldForSearch("Dock 48 dp")).isEqualTo("dock 48 dp")
        assertThat(foldForSearch("Größe")).isEqualTo("große")
    }
}
