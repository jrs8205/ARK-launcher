package org.arkikeskus.launcher.feature.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A provider's preview layout is foreign code rendered inside the HOME process: whatever it throws
 * while measuring, laying out or drawing must degrade to the fallback preview, never crash home.
 */
class WidgetPreviewContainerTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private class Hostile(context: Context, val failIn: String) : View(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (failIn == "measure") throw IllegalStateException("Circular dependencies cannot exist in RelativeLayout")
            setMeasuredDimension(10, 10)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            if (failIn == "layout") throw IllegalStateException("layout")
        }

        override fun onDraw(canvas: Canvas) {
            if (failIn == "draw") throw IllegalArgumentException("Software rendering does not support hardware bitmaps")
        }
    }

    private fun render(failIn: String): Boolean {
        var failed = false
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val container = WidgetPreviewContainer(context).apply {
                widgetWidth = 200
                widgetHeight = 100
                onRenderFailed = { failed = true }
                addView(Hostile(context, failIn), FrameLayout.LayoutParams(-1, -1))
            }
            container.measure(
                MeasureSpec.makeMeasureSpec(400, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(200, MeasureSpec.EXACTLY),
            )
            container.layout(0, 0, 400, 200)
            container.draw(Canvas(Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)))
        }
        instrumentation.waitForIdleSync()
        return failed
    }

    @Test fun aPreviewThatThrowsWhileMeasuringFallsBack() {
        assertThat(render("measure")).isTrue()
    }

    @Test fun aPreviewThatThrowsWhileLayingOutFallsBack() {
        assertThat(render("layout")).isTrue()
    }

    @Test fun aPreviewThatThrowsWhileDrawingFallsBack() {
        assertThat(render("draw")).isTrue()
    }
}
