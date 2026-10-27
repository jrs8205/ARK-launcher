package org.arkikeskus.launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AppUsageRepositoryTest {

    private val half = AppUsageRepository.HALF_LIFE_MS

    @Test
    fun `recordLaunch survives a write the storage refuses`() = runTest {
        // Internal storage full: DataStore throws from edit. The record is fire-and-forget on the
        // application scope, where an escaping exception would kill the home process after each launch.
        val refusing = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flowOf(emptyPreferences())
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IOException("No space left on device")
        }

        AppUsageRepository(refusing).recordLaunch("com.a/A/0")
    }

    @Test
    fun `currentScore halves across one half-life`() {
        val stat = UsageStat(score = 4f, lastUsed = 0L)
        assertThat(AppUsageRepository.currentScore(stat, half)).isWithin(0.001f).of(2f)
    }

    @Test
    fun `currentScore does not inflate when the clock goes backwards`() {
        val stat = UsageStat(score = 4f, lastUsed = half)
        // atMillis before lastUsed → elapsed clamps to 0 → no growth.
        assertThat(AppUsageRepository.currentScore(stat, 0L)).isWithin(0.001f).of(4f)
    }

    @Test
    fun `applyLaunch on a new app yields score 1 at the launch time`() {
        val stat = AppUsageRepository.applyLaunch(null, 1234L)
        assertThat(stat.score).isWithin(0.001f).of(1f)
        assertThat(stat.lastUsed).isEqualTo(1234L)
    }

    @Test
    fun `applyLaunch one half-life later decays then adds one`() {
        val prev = UsageStat(score = 1f, lastUsed = 0L)
        val stat = AppUsageRepository.applyLaunch(prev, half)
        assertThat(stat.score).isWithin(0.001f).of(1.5f) // 0.5 decay + 1
        assertThat(stat.lastUsed).isEqualTo(half)
    }

    @Test
    fun `a recently used app outranks an older more-used app`() {
        val day = 24L * 60 * 60 * 1000
        val now = 100L * day
        val recent = UsageStat(score = 3f, lastUsed = now - 1 * day)
        val older = UsageStat(score = 5f, lastUsed = now - 30 * day)
        assertThat(AppUsageRepository.currentScore(recent, now))
            .isGreaterThan(AppUsageRepository.currentScore(older, now))
    }

    @Test
    fun `recordLaunch persists a stat through the data store`() = runTest {
        val repo = AppUsageRepository(InMemoryDataStore())
        repo.recordLaunch("com.example/Main/0")
        val stat = repo.usage.first()["com.example/Main/0"]
        assertThat(stat).isNotNull()
        assertThat(stat!!.score).isWithin(0.001f).of(1f)
    }
}
