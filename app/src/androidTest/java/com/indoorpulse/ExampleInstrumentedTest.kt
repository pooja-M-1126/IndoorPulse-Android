package com.indoorpulse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*
import java.io.File

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.indoorpulse", appContext.packageName)
    }

    @Test
    fun launcherResolvesToAdaptiveIcon() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        assertTrue(icon is AdaptiveIconDrawable)
        if (InstrumentationRegistry.getArguments().getString("captureUi") == "true") {
            val bitmap = Bitmap.createBitmap(384, 384, Bitmap.Config.ARGB_8888)
            icon.setBounds(0, 0, bitmap.width, bitmap.height)
            icon.draw(Canvas(bitmap))
            File(context.cacheDir, "ui-audit-launcher.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
