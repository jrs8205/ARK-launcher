package org.arkikeskus.launcher.feature.updater

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SemVerTest {
    @Test fun patch_bump_is_newer() { assertThat(SemVer.isNewer("0.3.1", "0.3.0")).isTrue() }
    @Test fun minor_bump_is_newer() { assertThat(SemVer.isNewer("0.4.0", "0.3.9")).isTrue() }
    @Test fun double_digit_minor_is_newer() { assertThat(SemVer.isNewer("0.10.0", "0.9.0")).isTrue() }
    @Test fun equal_is_not_newer() { assertThat(SemVer.isNewer("0.3.0", "0.3.0")).isFalse() }
    @Test fun older_is_not_newer() { assertThat(SemVer.isNewer("0.2.9", "0.3.0")).isFalse() }
    @Test fun v_prefix_tolerated() { assertThat(SemVer.isNewer("v0.3.1", "0.3.0")).isTrue() }
    @Test fun shorter_components_treated_as_zero() { assertThat(SemVer.isNewer("1", "0.9.9")).isTrue() }
    @Test fun malformed_candidate_is_not_newer() { assertThat(SemVer.isNewer("garbage", "0.3.0")).isFalse() }
}
