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
        ),
    )

    @Test
    fun round_trips_document() {
        val decoded = BackupCodec.decode(BackupCodec.encode(sampleDoc()))
        assertThat(decoded).isEqualTo(sampleDoc())
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
