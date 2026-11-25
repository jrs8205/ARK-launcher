package org.arkikeskus.launcher.data

import android.Manifest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.search.ContactDataSource
import org.arkikeskus.launcher.data.search.ContactSearchProvider
import org.arkikeskus.launcher.data.search.PermissionChecker
import org.arkikeskus.launcher.data.search.RawContact
import org.arkikeskus.launcher.model.SearchResult
import org.junit.Test

class ContactSearchProviderTest {

    private val sample = listOf(RawContact("Liisa", "content://c/1", "+358 1", "content://p/1", 1L))

    private fun repo(searchContacts: Boolean): SettingsRepository {
        val r = SettingsRepository(InMemoryDataStore())
        runTest { r.setSearchContacts(searchContacts) }
        return r
    }

    private fun provider(
        searchContacts: Boolean,
        granted: Boolean,
        data: List<RawContact> = sample,
    ) = ContactSearchProvider(
        settingsRepository = repo(searchContacts),
        permissionChecker = PermissionChecker { granted },
        dataSource = object : ContactDataSource {
            override suspend fun search(query: String, limit: Int) = data
        },
    )

    @Test fun `disabled when setting off`() = runTest {
        assertThat(provider(searchContacts = false, granted = true).isEnabled()).isFalse()
    }

    @Test fun `disabled when permission missing`() = runTest {
        assertThat(provider(searchContacts = true, granted = false).isEnabled()).isFalse()
    }

    @Test fun `enabled when setting on and permission granted`() = runTest {
        assertThat(provider(searchContacts = true, granted = true).isEnabled()).isTrue()
    }

    @Test fun thePlatformMatchesTheSpellingTheUserTyped() = runTest {
        val queries = mutableListOf<String>()
        val contact = ContactSearchProvider(SettingsRepository(InMemoryDataStore()), PermissionChecker { true },
            object : ContactDataSource {
                override suspend fun search(query: String, limit: Int): List<RawContact> {
                    queries += query
                    return sample.filter { it.name.contains(query, ignoreCase = true) }
                }
            },
        )
        val lower = contact.query("li")
        assertThat(lower).hasSize(1)
        assertThat(contact.query("Li")).isEqualTo(lower)
        assertThat(queries).containsExactly("li", "Li").inOrder()
    }

    @Test fun `maps raw contacts to results`() = runTest {
        val r = provider(searchContacts = true, granted = true).query("li")
            .filterIsInstance<SearchResult.Contact>().single()
        assertThat(r.name).isEqualTo("Liisa")
        assertThat(r.number).isEqualTo("+358 1")
        assertThat(r.contactId).isEqualTo(1L)
    }

    @Suppress("unused")
    private fun perm() = Manifest.permission.READ_CONTACTS
}
