package org.arkikeskus.launcher.data

import org.arkikeskus.launcher.model.IconEpochs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IconEpochsTest {

    @Test
    fun `unknown package resolves to the global generation`() {
        assertEquals(0, IconEpochs().of("com.example"))
        assertEquals(2, IconEpochs(global = 2).of("com.example"))
    }

    @Test
    fun `bump advances only the named packages`() {
        val e = IconEpochs().bump(listOf("a", "b"))
        assertEquals(1, e.of("a"))
        assertEquals(1, e.of("b"))
        assertEquals(0, e.of("c"))
    }

    @Test
    fun `repeated bumps keep increasing`() {
        val e = IconEpochs().bump(listOf("a")).bump(listOf("a")).bump(listOf("a"))
        assertEquals(3, e.of("a"))
    }

    @Test
    fun `global bump shifts every package including already-bumped ones`() {
        val e = IconEpochs().bump(listOf("a")).bumpGlobal()
        assertEquals(2, e.of("a"))
        assertEquals(1, e.of("other"))
    }

    @Test
    fun `epoch never reuses an older value across mixed bumps`() {
        // Per-package and global bumps interleaved: every bump touching "a" must strictly increase
        // its epoch, and a bump of an unrelated package must leave it untouched.
        var e = IconEpochs()
        val start = e.of("a")
        e = e.bump(listOf("a"))
        val afterPackageBump = e.of("a")
        e = e.bumpGlobal()
        val afterGlobalBump = e.of("a")
        e = e.bump(listOf("b"))
        val afterUnrelatedBump = e.of("a")
        e = e.bump(listOf("a"))
        val afterSecondPackageBump = e.of("a")
        assertTrue(start < afterPackageBump)
        assertTrue(afterPackageBump < afterGlobalBump)
        assertEquals(afterGlobalBump, afterUnrelatedBump)
        assertTrue(afterUnrelatedBump < afterSecondPackageBump)
    }
}
