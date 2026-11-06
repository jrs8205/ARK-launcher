package org.arkikeskus.launcher.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The app labels survive a process start: a scan hands out what the last scan persisted, at once,
 * reads only the labels it has no entry for, and re-reads the persisted ones once in the background
 * to correct what changed while the launcher was not running. A package event forgets that package's
 * entries — and a reading the event overtook; another locale is another set of labels; the store
 * failing (a full disk) costs nothing but the persistence.
 */
class AppLabelCacheTest {
    private val store = InMemoryAppLabelStore()
    private val cache = AppLabelCache(store)

    private fun reading(label: String, packageName: String) = AppLabelCache.Reading(label, cache.generation(packageName))

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

        cache.put("com.new/new.Main/0", "New", cache.generation("com.new"))

        assertThat(cache.label("com.new/new.Main/0")).isEqualTo("New")
        assertThat(cache.unverified()).isEmpty()
    }

    @Test fun verificationCorrectsAChangedLabelAndReportsIt() = runTest {
        store.stored["en-US"] = mapOf("com.mail/mail.Main/0" to "Mail", "com.chat/chat.Main/0" to "Chat")
        cache.prepare("en-US")

        val changed = cache.verify(mapOf("com.mail/mail.Main/0" to reading("Mail", "com.mail"), "com.chat/chat.Main/0" to reading("Chat 2", "com.chat")))

        assertThat(changed).isTrue()
        assertThat(cache.label("com.chat/chat.Main/0")).isEqualTo("Chat 2")
        assertThat(cache.unverified()).isEmpty()
    }

    @Test fun verificationOfUnchangedLabelsReportsNothing() = runTest {
        store.stored["en-US"] = mapOf("com.mail/mail.Main/0" to "Mail")
        cache.prepare("en-US")

        assertThat(cache.verify(mapOf("com.mail/mail.Main/0" to reading("Mail", "com.mail")))).isFalse()
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

    /** A package updated while its label was being read: the reading may be the OLD label, and the
     *  rescan the event queued would take it as a cache hit — so a reading the event overtook is dropped
     *  (its generation is stale) and the rescan reads the label again. */
    @Test fun aPackageEventDuringAReadDropsThatReading() = runTest {
        store.stored["en-US"] = mapOf("com.mail/mail.Main/0" to "Mail", "com.chat/chat.Main/0" to "Chat")
        cache.prepare("en-US")
        val mailBefore = cache.generation("com.mail")
        val chatBefore = cache.generation("com.chat")
        val newBefore = cache.generation("com.new")

        cache.invalidatePackage("com.mail")
        cache.invalidatePackage("com.new")

        cache.put("com.new/new.Main/0", "Old new", newBefore)
        assertThat(cache.label("com.new/new.Main/0")).isNull()
        cache.put("com.other/other.Main/0", "Other", cache.generation("com.other"))
        assertThat(cache.label("com.other/other.Main/0")).isEqualTo("Other")

        val changed = cache.verify(
            mapOf("com.mail/mail.Main/0" to AppLabelCache.Reading("Old mail", mailBefore), "com.chat/chat.Main/0" to AppLabelCache.Reading("Chat 2", chatBefore)),
        )

        assertThat(changed).isTrue()
        assertThat(cache.label("com.mail/mail.Main/0")).isNull()
        assertThat(cache.label("com.chat/chat.Main/0")).isEqualTo("Chat 2")
        assertThat(cache.unverified()).isEmpty()

        // After the rescan reads it with the current generation, it is kept again.
        cache.put("com.mail/mail.Main/0", "New mail", cache.generation("com.mail"))
        assertThat(cache.label("com.mail/mail.Main/0")).isEqualTo("New mail")
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

        cache.put("com.new/new.Main/0", "New", cache.generation("com.new"))
        cache.persist()
        assertThat(store.writes).isEqualTo(1)
        assertThat(store.stored["en-US"]).containsExactly("com.mail/mail.Main/0", "Mail", "com.new/new.Main/0", "New")

        cache.persist()
        assertThat(store.writes).isEqualTo(1)
    }

    /** A full disk must not take the HOME process down: the write is dropped (the cache is rebuilt from
     *  the apps anyway) and tried again at the next persist. */
    @Test fun aFailingWriteIsDroppedAndTriedAgainAtTheNextPersist() = runTest {
        cache.prepare("en-US")
        cache.put("com.new/new.Main/0", "New", cache.generation("com.new"))
        store.failWrites = true

        cache.persist()
        assertThat(store.writes).isEqualTo(0)

        store.failWrites = false
        cache.persist()
        assertThat(store.writes).isEqualTo(1)
        assertThat(store.stored["en-US"]).containsExactly("com.new/new.Main/0", "New")
    }

    @Test fun aFailingReadStartsWithNoLabels() = runTest {
        store.stored["en-US"] = mapOf("com.mail/mail.Main/0" to "Mail")
        store.failReads = true

        cache.prepare("en-US")

        assertThat(cache.label("com.mail/mail.Main/0")).isNull()
        assertThat(cache.unverified()).isEmpty()
    }

    @Test fun aLocaleChangeStartsFromThatLocalesLabels() = runTest {
        store.stored["en-US"] = mapOf("com.mail/mail.Main/0" to "Mail")
        store.stored["fi-FI"] = mapOf("com.mail/mail.Main/0" to "Posti")
        cache.prepare("en-US")
        cache.put("com.new/new.Main/0", "New", cache.generation("com.new"))

        cache.prepare("fi-FI")

        assertThat(cache.label("com.mail/mail.Main/0")).isEqualTo("Posti")
        assertThat(cache.label("com.new/new.Main/0")).isNull()
        assertThat(cache.unverified()).containsExactly("com.mail/mail.Main/0")

        cache.put("com.new/new.Main/0", "Uusi", cache.generation("com.new"))
        cache.persist()
        assertThat(store.stored["fi-FI"]).containsExactly("com.mail/mail.Main/0", "Posti", "com.new/new.Main/0", "Uusi")
        assertThat(store.stored["en-US"]).containsExactly("com.mail/mail.Main/0", "Mail")
    }

    @Test fun preparingTheSameLocaleAgainKeepsWhatThisProcessRead() = runTest {
        store.stored["en-US"] = mapOf("com.mail/mail.Main/0" to "Mail")
        cache.prepare("en-US")
        cache.verify(mapOf("com.mail/mail.Main/0" to reading("Mail", "com.mail")))
        cache.put("com.new/new.Main/0", "New", cache.generation("com.new"))

        cache.prepare("en-US")

        assertThat(cache.label("com.new/new.Main/0")).isEqualTo("New")
        assertThat(cache.unverified()).isEmpty()
    }
}
