package org.arkikeskus.launcher.feature.appdrawer

import android.os.Process
import android.os.UserHandle
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.data.DrawerFolder
import org.arkikeskus.launcher.data.WorkProfile
import org.arkikeskus.launcher.model.AppItem
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How the drawer's idle app list splits into the Personal grid, the Work grid and the folders:
 * hidden apps leave both grids, a folder keeps every member the user put in it (a work app among
 * them is still listed on the Work tab), and the tab row exists only when the Work tab has
 * something to show.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DrawerPartitionTest {
    private val own: UserHandle = Process.myUserHandle()
    private val workUser: UserHandle = UserHandle.getUserHandleForUid(10 * 100_000)

    private fun personal(name: String) =
        AppItem(packageName = "com.$name", className = "$name.Main", user = own, userSerial = 0, label = name)
    private fun work(name: String) =
        AppItem(packageName = "com.$name", className = "$name.Main", user = workUser, userSerial = 10, label = name)

    private val mail = personal("mail")
    private val maps = personal("maps")
    private val slack = work("slack")
    private val teams = work("teams")
    private val all = listOf(mail, maps, slack, teams)
    private val chatFolder = DrawerFolder(id = 1, name = "Chat", appKeys = listOf(slack.key, mail.key))

    @Test fun withoutAWorkSerialEverythingIsPersonalAsBefore() {
        val p = partitionDrawer(all, hidden = emptySet(), folders = emptyList(), workSerial = null, sortFolders = false)

        assertThat(p.personal).isEqualTo(all)
        assertThat(p.work).isEmpty()
        assertThat(p.folders).isEmpty()
        assertThat(p.folderCandidates).isEqualTo(all)
    }

    @Test fun workAppsLeaveThePersonalGridAndFillTheWorkGrid() {
        val p = partitionDrawer(all, hidden = emptySet(), folders = emptyList(), workSerial = 10, sortFolders = false)

        assertThat(p.personal).containsExactly(mail, maps).inOrder()
        assertThat(p.work).containsExactly(slack, teams).inOrder()
    }

    @Test fun hiddenAppsAreGoneFromBothGrids() {
        val p = partitionDrawer(all, hidden = setOf(maps.key, teams.key), folders = emptyList(), workSerial = 10, sortFolders = false)

        assertThat(p.personal).containsExactly(mail)
        assertThat(p.work).containsExactly(slack)
    }

    @Test fun aFolderKeepsItsWorkMemberAndTheWorkGridListsItToo() {
        val p = partitionDrawer(all, hidden = emptySet(), folders = listOf(chatFolder), workSerial = 10, sortFolders = false)

        assertThat(p.folders).containsExactly(DrawerFolderUi(1, "Chat", listOf(slack, mail)))
        assertThat(p.personal).containsExactly(maps)
        assertThat(p.work).containsExactly(slack, teams).inOrder()
    }

    @Test fun folderCandidatesAreTheUnfolderedAppsOfBothProfiles() {
        val p = partitionDrawer(all, hidden = emptySet(), folders = listOf(chatFolder), workSerial = 10, sortFolders = false)

        assertThat(p.folderCandidates).containsExactly(maps, teams).inOrder()
    }

    /** The folders come in the order the user keeps them (creation, then moves) unless asked for
     *  A–Z, which ignores case like the app list. */
    @Test fun foldersFollowTheStoredOrderUnlessSortedByName() {
        val tools = DrawerFolder(id = 2, name = "Tools", appKeys = emptyList())
        val games = DrawerFolder(id = 3, name = "games", appKeys = emptyList())
        val stored = listOf(tools, games, chatFolder)

        val kept = partitionDrawer(all, hidden = emptySet(), folders = stored, workSerial = null, sortFolders = false)
        val sorted = partitionDrawer(all, hidden = emptySet(), folders = stored, workSerial = null, sortFolders = true)

        assertThat(kept.folders.map { it.name }).containsExactly("Tools", "games", "Chat").inOrder()
        assertThat(sorted.folders.map { it.name }).containsExactly("Chat", "games", "Tools").inOrder()
    }

    @Test fun pausedProfileWithNoVisibleAppsStillHasATab() {
        assertThat(workTabVisible(WorkProfile(userSerial = 10, paused = true), emptyList())).isTrue()
    }

    @Test fun anUnpausedProfileWithNoVisibleAppsHasNoTab() {
        assertThat(workTabVisible(WorkProfile(userSerial = 10, paused = false), emptyList())).isFalse()
        assertThat(workTabVisible(null, listOf(slack))).isFalse()
        assertThat(workTabVisible(WorkProfile(userSerial = 10, paused = false), listOf(slack))).isTrue()
    }

    @Test fun effectiveTabFallsBackToPersonalWithoutAWorkTab() {
        assertThat(effectiveTab(DrawerTab.Work, hasWorkTab = false)).isEqualTo(DrawerTab.Personal)
        assertThat(effectiveTab(DrawerTab.Work, hasWorkTab = true)).isEqualTo(DrawerTab.Work)
        assertThat(effectiveTab(DrawerTab.Personal, hasWorkTab = true)).isEqualTo(DrawerTab.Personal)
    }
}
