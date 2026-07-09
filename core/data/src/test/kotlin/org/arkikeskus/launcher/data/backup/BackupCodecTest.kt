package org.arkikeskus.launcher.data.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.Assert.assertThrows
import java.math.BigDecimal

class BackupCodecTest {

    private fun sampleDoc() = BackupDocument(
        format = BackupCodec.FORMAT,
        appVersion = "0.2.0",
        createdAt = 1_719_500_000_000L,
        settings = mapOf(
            "dock_enabled" to true,
            "home_columns" to 4,
            "dock_opacity" to BigDecimal("0.35"),
            "dock_favorites" to "a/b/0\nc/d/0",
        ),
        homeItems = listOf(
            BackupItem(1, -1, null, "com.x", "com.x.Main", true, null, 0, 0, 1),
            BackupItem(2, -1, "Tools", "", "", false, null, 0, 1, 0),
            BackupItem(3, 2, null, "com.y", "com.y.Main", true, null, 0, 0, 0),
            // A widget row: 2×2, provider set, no appWidgetId (device-local, never stored).
            BackupItem(4, -1, null, "", "", true, null, 1, 2, 3, spanX = 2, spanY = 2, widgetProvider = "com.w/com.w.Prov"),
            // A built-in launcher widget (format 3): type set, no provider.
            BackupItem(5, -1, null, "", "", true, null, 0, 0, 4, spanX = 4, spanY = 2, builtinType = "smartspace"),
        ),
    )

    @Test
    fun format_is_3() {
        assertThat(BackupCodec.FORMAT).isEqualTo(3)
    }

    @Test
    fun round_trips_document() {
        val decoded = BackupCodec.decode(BackupCodec.encode(sampleDoc()))
        assertThat(decoded).isEqualTo(sampleDoc())
    }

    @Test
    fun decode_accepts_format_1_defaulting_widget_fields() {
        // An old (format 1) backup has no spanX/spanY/widgetProvider — they must default to a 1×1
        // non-widget row rather than being rejected.
        val json = """
            {"format":1,"appVersion":"0.1.0","createdAt":0,"settings":{},
             "homeItems":[{"id":1,"containerId":-1,"folderName":null,"packageName":"com.x",
             "className":"com.x.Main","mainProfile":true,"shortcutId":null,"page":0,"cellX":0,"cellY":0}]}
        """.trimIndent()
        val doc = BackupCodec.decode(json)
        val item = doc.homeItems.single()
        assertThat(item.spanX).isEqualTo(1)
        assertThat(item.spanY).isEqualTo(1)
        assertThat(item.widgetProvider).isNull()
    }

    @Test
    fun decode_accepts_format_2_defaulting_builtin_type() {
        // A format-2 backup has no builtinType — it must default to null (a plain row).
        val json = """
            {"format":2,"appVersion":"0.6.0","createdAt":0,"settings":{},
             "homeItems":[{"id":1,"containerId":-1,"folderName":null,"packageName":"","className":"",
             "mainProfile":true,"shortcutId":null,"page":0,"cellX":0,"cellY":0,
             "spanX":2,"spanY":2,"widgetProvider":"com.w/P"}]}
        """.trimIndent()
        val item = BackupCodec.decode(json).homeItems.single()
        assertThat(item.builtinType).isNull()
        assertThat(item.widgetProvider).isEqualTo("com.w/P")
    }

    @Test
    fun decode_rejects_unknown_format() {
        val json = """{"format":99,"appVersion":"x","createdAt":0,"settings":{},"homeItems":[]}"""
        assertThrows(BackupFormatException::class.java) { BackupCodec.decode(json) }
    }

    @Test
    fun decode_rejects_missing_format() {
        val json = """{"appVersion":"x","createdAt":0,"settings":{},"homeItems":[]}"""
        assertThrows(BackupFormatException::class.java) { BackupCodec.decode(json) }
    }
}
