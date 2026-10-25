package org.arkikeskus.launcher.feature.backup

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
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
import org.robolectric.shadows.ShadowDialog

/** The dialog the "Open with" import shows over the app that handed the file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupImportDialogTest {
    @get:Rule val compose = createComposeRule()

    private val res = RuntimeEnvironment.getApplication().resources
    private var restores = 0
    private var leaves = 0

    /** A scroll container that holds [text], on itself or on a child. */
    private fun scrollableWith(text: String) = hasScrollAction() and (hasText(text) or hasAnyDescendant(hasText(text)))

    private fun show(state: BackupImportState) = compose.setContent {
        BackupImportDialog(state = state, onRestore = { restores++ }, onLeave = { leaves++ })
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
        assertThat(leaves).isEqualTo(0)
    }

    @Test fun cancelLeavesWithoutRestoring() {
        show(BackupImportState.Confirm)

        compose.onNodeWithText(res.getString(R.string.backup_cancel)).performClick()

        assertThat(leaves).isEqualTo(1)
        assertThat(restores).isEqualTo(0)
    }

    /** A provider that never answers must not trap the user in the dialog. */
    @Test fun whileTheFileIsReadItCanBeCancelled() {
        show(BackupImportState.Reading)

        compose.onNodeWithText(res.getString(R.string.backup_restoring)).assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.backup_cancel)).performClick()

        assertThat(leaves).isEqualTo(1)
    }

    @Test fun whileTheLayoutIsReplacedItSaysSoAndOffersNoWayOut() {
        show(BackupImportState.Restoring)

        compose.onNodeWithText(res.getString(R.string.backup_restoring)).assertIsDisplayed()
        compose.onNodeWithText(res.getString(R.string.backup_cancel)).assertDoesNotExist()
        compose.onNodeWithText(res.getString(android.R.string.ok)).assertDoesNotExist()
    }

    /** On a small screen at a large font the advice is taller than the dialog: it must scroll, or its
     *  end — the part that says what works instead — is cut off. */
    @Test fun aLongOutcomeCanBeScrolledToItsEnd() {
        show(BackupImportState.Done(BackupEvent.ReadBlocked))

        compose.onNode(scrollableWith(res.getString(R.string.backup_import_read_blocked))).assertExists()
    }

    @Test fun theQuestionCanBeScrolledToo() {
        show(BackupImportState.Confirm)

        compose.onNode(scrollableWith(res.getString(R.string.backup_restore_confirm_msg))).assertExists()
    }

    /** The dialog replaces the layout on one tap, over another app: a touch that reaches it through an
     *  overlay (tapjacking) is dropped, on every Android version. */
    @Test fun touchesThroughAnOverlayAreDropped() {
        show(BackupImportState.Confirm)
        compose.waitForIdle()

        val dialog = ShadowDialog.getLatestDialog()
        assertThat(dialog.window!!.decorView.filterTouchesWhenObscured).isTrue()
    }

    /** A finger tap on Restore that arrives with [flags], sent the way the system sends it: to the
     *  dialog window's decor view. */
    private fun tapRestore(flags: Int) {
        val dialog = ShadowDialog.getLatestDialog()
        val at = compose.onNodeWithText(res.getString(R.string.backup_restore_confirm_ok))
            .fetchSemanticsNode().boundsInWindow.center
        val down = SystemClock.uptimeMillis()
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to down, MotionEvent.ACTION_UP to down + 50)) {
            val event = MotionEvent.obtain(
                down, time, action, 1,
                arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }),
                arrayOf(MotionEvent.PointerCoords().apply { x = at.x; y = at.y; pressure = 1f; size = 1f }),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags,
            )
            dialog.window!!.decorView.dispatchTouchEvent(event)
            event.recycle()
        }
        compose.waitForIdle()
    }

    @Test fun aPlainTapOnRestoreRestores() {
        show(BackupImportState.Confirm)
        compose.waitForIdle()

        tapRestore(flags = 0)

        assertThat(restores).isEqualTo(1)
    }

    /** On Android 11 nothing hides other apps' overlays: one can cover the warning and leave Restore
     *  in sight. A touch through a window that covers only part of the dialog is dropped as well. */
    @Config(sdk = [30])
    @Test fun aTapThroughAPartlyCoveringOverlayIsDropped() {
        show(BackupImportState.Confirm)
        compose.waitForIdle()

        tapRestore(flags = MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)

        assertThat(restores).isEqualTo(0)
    }

    @Test fun aTapThroughACoveringOverlayIsDropped() {
        show(BackupImportState.Confirm)
        compose.waitForIdle()

        tapRestore(flags = MotionEvent.FLAG_WINDOW_IS_OBSCURED)

        assertThat(restores).isEqualTo(0)
    }

    @Test fun theOutcomeIsShownUntilOk() {
        show(BackupImportState.Done(BackupEvent.ReadBlocked))

        compose.onNodeWithText(res.getString(R.string.backup_import_read_blocked)).assertIsDisplayed()
        compose.onNodeWithText(res.getString(android.R.string.ok)).performClick()

        assertThat(leaves).isEqualTo(1)
        assertThat(restores).isEqualTo(0)
    }
}
