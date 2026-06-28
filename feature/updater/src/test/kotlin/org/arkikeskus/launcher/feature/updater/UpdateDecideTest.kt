package org.arkikeskus.launcher.feature.updater

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UpdateDecideTest {
    private val repo = UpdateRepository(okhttp3.OkHttpClient())
    private val rel = ParsedRelease("v0.4.0", "- notes", "https://x/app.apk", 123L)

    @Test fun newer_release_returns_info() {
        val info = repo.decide("0.3.0", rel)!!
        assertThat(info.versionName).isEqualTo("0.4.0")
        assertThat(info.apkUrl).isEqualTo("https://x/app.apk")
        assertThat(info.notes).isEqualTo("- notes")
    }
    @Test fun same_version_returns_null() {
        assertThat(repo.decide("0.4.0", rel)).isNull()
    }
    @Test fun null_parsed_returns_null() {
        assertThat(repo.decide("0.3.0", null)).isNull()
    }
}
