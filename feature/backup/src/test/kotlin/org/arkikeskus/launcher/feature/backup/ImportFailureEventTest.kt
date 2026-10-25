package org.arkikeskus.launcher.feature.backup

import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.data.backup.BackupFormatException
import org.json.JSONException
import org.junit.Test

/** What the screen is told when an import fails, by where it failed. */
class ImportFailureEventTest {

    @Test fun aRefusedReadIsBlocked() {
        assertThat(importFailureEvent(BackupReadBlockedException(SecurityException("no access"))))
            .isEqualTo(BackupEvent.ReadBlocked)
    }

    @Test fun aReadThatTookTooLongTimedOut() {
        assertThat(importFailureEvent(BackupReadTimeoutException())).isEqualTo(BackupEvent.ReadTimedOut)
    }

    /** Only the read is wrapped: a refusal later on (restoring) is not about picking the file. */
    @Test fun aSecurityExceptionOutsideTheReadIsAPlainFailure() {
        assertThat(importFailureEvent(SecurityException("profile gone")))
            .isEqualTo(BackupEvent.Failed("profile gone"))
    }

    @Test fun otherFailuresKeepTheirEvents() {
        assertThat(importFailureEvent(BackupTooLargeException())).isEqualTo(BackupEvent.TooLarge)
        assertThat(importFailureEvent(BackupFormatException("bad"))).isEqualTo(BackupEvent.InvalidFile)
        assertThat(importFailureEvent(JSONException("bad"))).isEqualTo(BackupEvent.InvalidFile)
        assertThat(importFailureEvent(IllegalStateException("App list unavailable")))
            .isEqualTo(BackupEvent.Failed("App list unavailable"))
    }
}
