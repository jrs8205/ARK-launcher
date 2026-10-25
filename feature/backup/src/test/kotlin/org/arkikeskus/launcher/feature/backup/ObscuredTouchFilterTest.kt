package org.arkikeskus.launcher.feature.backup

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Window
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/** The import dialog's touch filter: a gesture that comes through another window goes nowhere. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ObscuredTouchFilterTest {
    private val seen = mutableListOf<Int>()
    private val window = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Window.Callback::class.java)) { _, method, args ->
        check(method.name == "dispatchTouchEvent") { method.name }
        seen += (args[0] as MotionEvent).actionMasked
        true
    } as Window.Callback
    private val filter = ObscuredTouchFilter(window)

    private fun send(action: Int, flags: Int = 0) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(
            now, now, action, 1,
            arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }),
            arrayOf(MotionEvent.PointerCoords().apply { x = 10f; y = 10f; pressure = 1f; size = 1f }),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags,
        )
        filter.dispatchTouchEvent(event)
        event.recycle()
    }

    @Test fun aGestureThroughAPartlyCoveringWindowIsDroppedWhole() {
        send(MotionEvent.ACTION_DOWN, MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)
        send(MotionEvent.ACTION_MOVE)
        send(MotionEvent.ACTION_UP)

        assertThat(seen).isEmpty()
    }

    /** An overlay that turns up mid-gesture ends it as cancelled: nothing is left half pressed. */
    @Test fun aGestureCoveredOnTheWayIsCancelled() {
        send(MotionEvent.ACTION_DOWN)
        send(MotionEvent.ACTION_MOVE, MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)
        send(MotionEvent.ACTION_UP, MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)

        assertThat(seen).containsExactly(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL).inOrder()
    }

    @Test fun theNextCleanGestureGoesThrough() {
        send(MotionEvent.ACTION_DOWN, MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        send(MotionEvent.ACTION_UP, MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        send(MotionEvent.ACTION_DOWN)
        send(MotionEvent.ACTION_UP)

        assertThat(seen).containsExactly(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).inOrder()
    }
}
