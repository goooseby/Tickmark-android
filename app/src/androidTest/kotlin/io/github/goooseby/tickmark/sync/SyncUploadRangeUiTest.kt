package io.github.goooseby.tickmark.sync

import android.graphics.Bitmap
import android.text.format.DateFormat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.goooseby.tickmark.ui.SyncUploadRangeDialog
import io.github.goooseby.tickmark.ui.TickmarkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SyncUploadRangeUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun changingAndCancellingDatesOnlyAffectsTheConfirmedRange() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val earlier = today.minusDays(1)
        val earlierLabel = earlier.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
        val todayLabel = "今天（${today.monthValue}月${today.dayOfMonth}日）"
        val locale = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.locales[0]
        val calendarLabel = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMMMEEEEd"), locale)
        var submitted: LocalDate? = null
        compose.setContent {
            TickmarkTheme(dark = InstrumentationRegistry.getArguments().getString("darkRange") == "true") {
                SyncUploadRangeDialog("测试手机", {}, true, {}, { submitted = it })
            }
        }

        compose.onNodeWithText(todayLabel).assertIsDisplayed()
        screenshot("today")
        compose.onNodeWithText("更改").performClick()
        compose.onNodeWithText("选择上传开始日期").assertIsDisplayed()
        val tomorrow = today.plusDays(1)
        if (tomorrow.month == today.month) {
            compose.onNodeWithText(tomorrow.format(calendarLabel)).assertIsNotEnabled()
        }
        if (earlier.month != today.month) compose.onNodeWithContentDescription("Previous month").performClick()
        compose.onNodeWithText(earlier.format(calendarLabel)).performClick()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText(todayLabel).assertExists()
        compose.runOnIdle { assertNull(submitted) }

        compose.onNodeWithText("更改").performClick()
        if (earlier.month != today.month) compose.onNodeWithContentDescription("Previous month").performClick()
        compose.onNodeWithText(earlier.format(calendarLabel)).performClick()
        screenshot("calendar")
        compose.onNodeWithText("使用此日期").performClick()
        compose.onNodeWithText(earlierLabel).assertIsDisplayed()
        screenshot("chosen")
        compose.onNodeWithText("开启同步").performClick()
        compose.runOnIdle { assertEquals(earlier, submitted) }
        compose.onNodeWithText("改为从今天起").performClick()
        compose.onNodeWithText(todayLabel).assertExists()
        compose.onNodeWithText("开启同步").performClick()
        compose.runOnIdle { assertEquals(today, submitted) }
    }

    private fun screenshot(name: String) {
        val registry = InstrumentationRegistry.getInstrumentation()
        val run = InstrumentationRegistry.getArguments().getString("captureRange") ?: return
        compose.waitForIdle()
        val bitmap = registry.uiAutomation.takeScreenshot() ?: return
        val dir = File(registry.targetContext.getExternalFilesDir(null), "validation").apply { mkdirs() }
        File(dir, "sync-upload-range-$run-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
