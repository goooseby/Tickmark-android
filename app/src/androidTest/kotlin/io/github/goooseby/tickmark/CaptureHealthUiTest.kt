package io.github.goooseby.tickmark

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.goooseby.tickmark.data.report.Records
import io.github.goooseby.tickmark.ui.CaptureSettings
import io.github.goooseby.tickmark.ui.TickmarkTheme
import org.junit.Rule
import org.junit.Test

class CaptureHealthUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsRecoveryEntryPointsAndStaleBackgroundCaptureWarning() {
        val stale = System.currentTimeMillis() - 13 * 3_600_000L
        compose.setContent {
            TickmarkTheme(false) {
                CaptureSettings(
                    Records(config = mapOf("lastBackgroundCapture" to stale.toString())),
                    access = true,
                    onPermission = {},
                    onPaused = {},
                    onRefresh = {},
                )
            }
        }

        compose.onNodeWithText("超过 12 小时没有后台补采").assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("电池优化"))
        compose.onNodeWithText("电池优化").assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("厂商后台限制"))
        compose.onNodeWithText("厂商后台限制").assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("上次后台补采"))
        compose.onNodeWithText("上次后台补采").assertExists()
    }

    @Test fun hidesStaleWarningWhilePaused() {
        compose.setContent {
            TickmarkTheme(false) {
                CaptureSettings(
                    Records(config = mapOf("paused" to "true")),
                    access = true,
                    onPermission = {},
                    onPaused = {},
                    onRefresh = {},
                )
            }
        }

        compose.onNodeWithText("超过 12 小时没有后台补采").assertDoesNotExist()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("上次后台补采"))
        compose.onNodeWithText("尚未在后台运行").assertExists()
    }
}
