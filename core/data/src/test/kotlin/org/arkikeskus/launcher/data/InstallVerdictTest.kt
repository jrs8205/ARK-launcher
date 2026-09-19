package org.arkikeskus.launcher.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The ghost-row sweep deletes rows on a `false` verdict, so only a DEFINITIVE answer may be false. */
class InstallVerdictTest {

    @Test
    fun a_failed_profile_lookup_keeps_the_rows() {
        val verdict = installVerdict<String>(Result.failure(SecurityException("locked"))) {
            error("must not query the app when the profile is unknown")
        }
        assertThat(verdict).isTrue()
    }

    @Test
    fun a_removed_profile_makes_the_rows_stale() {
        val verdict = installVerdict<String>(Result.success(null)) { Result.success(Unit) }
        assertThat(verdict).isFalse()
    }

    @Test
    fun an_installed_app_is_installed() {
        val verdict = installVerdict(Result.success("user")) { Result.success(Unit) }
        assertThat(verdict).isTrue()
    }

    @Test
    fun a_transient_app_lookup_failure_keeps_the_rows() {
        val verdict = installVerdict(Result.success("user")) { Result.failure<Unit>(RuntimeException("binder")) }
        assertThat(verdict).isTrue()
    }
}
