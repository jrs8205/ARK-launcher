package org.arkikeskus.launcher.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The app labels survive a process start: a scan hands out what the last scan persisted, at once,
 * reads only the labels it has no entry for, and re-reads the persisted ones once in the background
 * to correct what changed while the launcher was not running. A package event forgets that package's
 * entries; another locale is another set of labels.
 */
class AppLabelCacheTest {
    private val store = InMemoryAppLabelStore()
    private val cache = AppLabelCache(store)

    @Test fun thePersistedLabelsOfTheLocaleAreHandedOutAtOnce() = runTest {
        store.stored["en-US"] = mapOf("com.mail/mail.Main/0" to "Mail", "com.chat/chat.Main/0" to "Chat")
        store.stored["fi-FI"] = mapOf("com.mail/mail.Main/0" to "Posti")

        cache.prepare("en-US")

        assertThat(cache.label("com.mail/mail.Main/0")).isEqualTo("Mail")
        assertThat(cache.label("com.chat/chat.Main/0")).isEqualTo("Chat")
        assertThat(cache.label("com.new/new.Main/0")).isNull()
        assertThat(cache.unverified()).containsExactly("com.mail/mail.Main/0", "com.chat/chat.Main/0")
    }

    @Test fun aLabelReadInThisProcessIsKeptAndNeedsNoVerification() = runTest {
        cache.prepare("en-US")

        cache.put("com.new/new.Main/0", "New")

        assertThat(cache.label("com.new/new.Main/0")).isEqualTo("New")
        assertThat(cache.unverified()).isEmpty()
    }

    @Test fun verificationCorrectsAChangedLabelAndReportsIt() = runTest {
        store.stored["en-US"] = mapOf("com.mail/mail.Main/0" to "Mail", "com.chat/chat.Main/0" to "Chat")
        cache.prepare("en-US")

        val changed = cache.verify(mapOf("com.mail/mail.Main/0" to "Mail", "com.chat/chat.Main/0" to "Chat 2"))

        assertThat(changed).isTrue()
        assertThat(cache.label("com.chat/chat.Main/0")).isEqualTo("Chat 2")
        assertThat(cache.unverified()).isEmpty()
    }

    @Test fun verificationOfUnchangedLabelsReportsNothing() = runTest {
        store.stored["en-US"] = mapOf("com.mail/mail.Main/0" to "Mail")
        cache.prepare("en-US")

        assertThat(cache.verify(mapOf("com.mail/mail.Main/0" to "Mail"))).isFalse()
        assertThat(cache.unverified()).isEmpty()
    }

    @Test fun aPackageEventForgetsThatPackagesEntriesOnly() = runTest {
        store.stored["en-US"] = mapOf("com.mail/mail.Main/0" to "Mail", "com.mail/mail.Other/0" to "Mail 2", "com.chat/chat.Main/0" to "Chat")
        cache.prepare("en-US")

        cache.invalidatePackage("com.mail")

        assertThat(cache.label("com.mail/mail.Main/0")).isNull()
        assertThat(cache.label("com.mail/mail.Other/0")).isNull()
        assertThat(cache.label("com.chat/chat.Main/0")).isEqualTo("Chat")
        assertThat(cache.unverified()).containsExactly("com.chat/chat.Main/0")
    }

    @Test fun retainDropsTheAppsNoLongerInstalled() = runTest {
        store.stored["en-US"] = mapOf("com.mail/mail.Main/0" to "Mail", "com.gone/gone.Main/0" to "Gone")
        cache.prepare("en-US")

        cache.retain(setOf("com.mail/mail.Main/0"))

        assertThat(cache.label("com.gone/gone.Main/0")).isNull()
        assertThat(cache.unverified()).containsExactly("com.mail/mail.Main/0")
    }

    @Test fun persistWritesOnlyWhatChangedSinceTheLastWrite() = runTest {
        store.stored["en-US"] = mapOf("com.mail/mail.Main/0" to "Mail")
        cache.prepare("en-US")

        cache.persist()
        assertThat(store.writes).isEqualTo(0)

        cache.put("com.new/new.Main/0", "New")
        cache.persist()
        assertThat(store.writes).isEqualTo(1)
        assertThat(store.stored["en-US"]).containsExactly("com.mail/mail.Main/0", "Mail", "com.new/new.Main/0", "New")

        cache.persist()
        assertThat(store.writes).isEqualTo(1)
    }

    @Test fun aLocaleChangeStartsFromThatLocalesLabels() = runTest {
        store.stored["en-US"] = mapOf("com.mail/mail.Main/0" to "Mail")
        store.stored["fi-FI"] = mapOf("com.mail/mail.Main/0" to "Posti")
        cache.prepare("en-US")
        cache.put("com.new/new.Main/0", "New")

        cache.prepare("fi-FI")

        assertThat(cache.label("com.mail/mail.Main/0")).isEqualTo("Posti")
        assertThat(cache.label("com.new/new.Main/0")).isNull()
        assertThat(cache.unverified()).containsExactly("com.mail/mail.Main/0")

        cache.put("com.new/new.Main/0", "Uusi")
        cache.persist()
        assertThat(store.stored["fi-FI"]).containsExactly("com.mail/mail.Main/0", "Posti", "com.new/new.Main/0", "Uusi")
        assertThat(store.stored["en-US"]).containsExactly("com.mail/mail.Main/0", "Mail")
    }

    @Test fun preparingTheSameLocaleAgainKeepsWhatThisProcessRead() = runTest {
        store.stored["en-US"] = mapOf("com.mail/mail.Main/0" to "Mail")
        cache.prepare("en-US")
        cache.verify(mapOf("com.mail/mail.Main/0" to "Mail"))
        cache.put("com.new/new.Main/0", "New")

        cache.prepare("en-US")

        assertThat(cache.label("com.new/new.Main/0")).isEqualTo("New")
        assertThat(cache.unverified()).isEmpty()
    }
}
