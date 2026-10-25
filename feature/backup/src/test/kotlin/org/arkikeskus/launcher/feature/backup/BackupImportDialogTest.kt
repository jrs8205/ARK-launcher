package org.arkikeskus.launcher.feature.backup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The dialog the "Open with" import shows over the app that handed the file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupImportDialogTest {
    @get:Rule val compose = createComposeRule()

    private val res = RuntimeEnvironment.getApplication().resources
    private var restores = 0
    private var finishes = 0

    private fun show(state: BackupImportState) = compose.setContent {
        BackupImportDialog(state = state, onRestore = { restores++ }, onFinish = { finishes++ })
    }

    @Test fun asksTheSameQuestionAsTheBackupScreen() {
        show(BackupImportState.Confirm)

        compose.onNodeWithText(res.getString(R.string.backup_restore_confirm_title)).assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.backup_restore_confirm_msg)).assertIsDisplayed()
    }

    @Test fun restoreRestores() {
        show(BackupImportState.Confirm)

        compose.onNodeWithText(res.getString(R.string.backup_restore_confirm_ok)).performClick()

        assertThat(restores).isEqualTo(1)
        assertThat(finishes).isEqualTo(0)
    }

    @Test fun cancelLeavesWithoutRestoring() {
        show(BackupImportState.Confirm)

        compose.onNodeWithText(res.getString(R.string.backup_cancel)).performClick()

        assertThat(finishes).isEqualTo(1)
        assertThat(restores).isEqualTo(0)
    }

    @Test fun whileRestoringItSaysSoAndOffersNoWayOut() {
        show(BackupImportState.Running)

        compose.onNodeWithText(res.getString(R.string.backup_restoring)).assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.backup_cancel)).assertDoesNotExist()
        compose.onNodeWithText(res.getString(android.R.string.ok)).assertDoesNotExist()
    }

    @Test fun theOutcomeIsShownUntilOk() {
        show(BackupImportState.Done(BackupEvent.ReadBlocked))

        compose.onNodeWithText(res.getString(R.string.backup_import_read_blocked)).assertIsDisplayed()
        compose.onNodeWithText(res.getString(android.R.string.ok)).performClick()

        assertThat(finishes).isEqualTo(1)
        assertThat(restores).isEqualTo(0)
    }
}
