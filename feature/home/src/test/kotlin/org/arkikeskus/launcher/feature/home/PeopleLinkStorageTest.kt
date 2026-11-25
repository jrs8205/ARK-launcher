package org.arkikeskus.launcher.feature.home

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.arkikeskus.launcher.data.NotificationBadgeRepository
import org.arkikeskus.launcher.data.PinnedPerson
import org.arkikeskus.launcher.data.search.ContentResolverContactDataSource
import org.arkikeskus.launcher.data.search.PermissionChecker
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PeopleLinkStorageTest {
    @Test fun aRefusedMergeKeepsThePeopleSeparateAndTheirPinsIntact() = runTest {
        val context: Application = RuntimeEnvironment.getApplication()
        val wiring = ProductionWiring(context, this)
        val owner = ViewModelStore()
        try {
            val settings = wiring.settingsRepository
            val pin = PinnedPerson("alias", "Alias", number = "123")
            settings.pinPerson(pin)
            val vm = PeopleWidgetViewModel(context, NotificationBadgeRepository(), settings, wiring.appRepository,
                ContentResolverContactDataSource(context), PermissionChecker { false })
            owner.put("people", vm)
            val alias = PeopleWidgetViewModel.Tile(pin.key, pin.name, pin, null, null)
            val target = PeopleWidgetViewModel.Tile("target", "Target", null, null, null)
            val scratch = File(wiring.settingsOnDisk.path + ".tmp")
            check(scratch.mkdir())

            val merge = vm.link(alias, target)
            whileTheMainLooperTurns { merge.join() }
            assertThat(merge.isCancelled).isFalse()
            assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(context.getString(org.arkikeskus.launcher.ui.R.string.notice_change_not_saved))
            assertThat(settings.peopleAliases.first()).isEmpty()
            assertThat(settings.pinnedPeople.first()).containsExactly(pin)

            if (scratch.exists()) check(scratch.delete())
            whileTheMainLooperTurns { vm.link(alias, target).join() }
            assertThat(settings.peopleAliases.first()).containsExactly(pin.key, target.key)
            assertThat(settings.pinnedPeople.first()).containsExactly(pin.copy(key = target.key, name = target.name))
        } finally {
            owner.clear()
            wiring.close()
        }
    }
}
