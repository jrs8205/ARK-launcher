package org.arkikeskus.launcher.feature.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDateTime
import java.util.Locale

class BackupFileNameTest {

    @Test fun nameCarriesDateAndTime() {
        assertThat(backupFileName("launcher-backup", LocalDateTime.of(2026, 10, 1, 7, 6)))
            .isEqualTo("launcher-backup-2026-10-01_07-06.json")
    }

    @Test fun digitsStayAsciiInEveryLocale() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        try {
            assertThat(backupFileName("launcher-backup", LocalDateTime.of(2026, 10, 1, 19, 45)))
                .isEqualTo("launcher-backup-2026-10-01_19-45.json")
        } finally {
            Locale.setDefault(previous)
        }
    }
}
