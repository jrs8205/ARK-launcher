package org.arkikeskus.launcher.feature.home

import android.app.Application
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.os.Bundle
import androidx.compose.runtime.saveable.SaverScope
import com.google.common.truth.Truth.assertThat
import org.arkikeskus.launcher.model.WidgetPlacement
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review round 7. A widget being added waits in saved state while the system's bind dialog or the widget's own
 * set-up screen is in front, and the launcher can be killed meanwhile. What is saved names the home profile the
 * widget was chosen on, so that it is stored for that profile and no other when the dialogs are done.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PendingWidgetBindSaverTest {
    private val provider = AppWidgetProviderInfo().apply { provider = ComponentName("com.widgets", "com.widgets.W") }
    private val scope = SaverScope { true }

    private fun saved(bind: PendingWidgetBind): Bundle = with(PendingWidgetBindSaver) { scope.save(bind) }!!

    @Test fun theProfileAWidgetWasChosenOnIsKeptThroughSavedState() {
        val bind = PendingWidgetBind(41, provider, restoreRowId = null, placement = WidgetPlacement(1, 2, 3, 2, 1), profile = 3)

        val restored = PendingWidgetBindSaver.restore(saved(bind))!!

        assertThat(restored.profile).isEqualTo(3)
        assertThat(restored.appWidgetId).isEqualTo(41)
        assertThat(restored.placement).isEqualTo(WidgetPlacement(1, 2, 3, 2, 1))
        assertThat(restored.restoreRowId).isNull()
    }

    @Test fun aPlaceholdersBindKeepsItsRowAndItsProfile() {
        val restored = PendingWidgetBindSaver.restore(saved(PendingWidgetBind(42, provider, restoreRowId = 17, profile = 2)))!!

        assertThat(restored.restoreRowId).isEqualTo(17)
        assertThat(restored.profile).isEqualTo(2)
    }

    /** What a version with one home screen saved has no profile in it: that widget was the first profile's. */
    @Test fun aBindSavedWithoutAProfileIsTheFirstProfiles() {
        val old = saved(PendingWidgetBind(43, provider, restoreRowId = null, profile = 5)).apply { remove("profile") }

        assertThat(PendingWidgetBindSaver.restore(old)!!.profile).isEqualTo(1)
    }
}
