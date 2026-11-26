package io.github.goooseby.tickmark

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.goooseby.tickmark.ui.UpdateDownloadProgress
import io.github.goooseby.tickmark.ui.UpdateScreen
import io.github.goooseby.tickmark.update.REPOSITORY_URL
import io.github.goooseby.tickmark.update.ReleaseClient
import io.github.goooseby.tickmark.update.ReleaseInfo
import io.github.goooseby.tickmark.update.UpdateController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class UpdateProgressUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun downloadStatusCanBecomeUnknownAfterAnActualPercentage() {
        val progress = mutableStateOf<Int?>(null)
        var cancelled = false
        compose.setContent { UpdateDownloadProgress(progress.value) { cancelled = true } }
        compose.onNodeWithText("取消下载").assertExists()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)).assertExists()

        compose.runOnIdle { progress.value = 37 }
        compose.onNodeWithText("取消下载 · 37%").assertExists()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.37f, 0f..1f))).assertExists()

        compose.runOnIdle { progress.value = null }
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)).assertExists()
        compose.onNodeWithText("取消下载").performClick()
        compose.runOnIdle { assertTrue(cancelled) }
    }

    @Test fun tappingDownloadKeepsTheScreenUsableWhileWaitingForHeaders() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "update-screen-test-${UUID.randomUUID()}"
        val connected = CountDownLatch(1)
        val finish = CountDownLatch(1)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val delayWorker = thread(isDaemon = true) {
                runCatching {
                    server.accept().use {
                        connected.countDown()
                        finish.await(30, TimeUnit.SECONDS)
                    }
                }
            }
            val client = object : ReleaseClient() {
                override fun latest() = ReleaseInfo(
                    "v99.0.0", "测试更新", "测试用下载", "$REPOSITORY_URL/releases/tag/v99.0.0",
                    "http://127.0.0.1:${server.localPort}/tickmark.apk", "a".repeat(64), 4_000_000,
                )
            }
            val controller = UpdateController(context, client, name)
            try {
                compose.setContent { UpdateScreen(controller) }
                compose.onNodeWithText("检查更新").performClick()
                compose.waitUntil(10_000) { controller.state.value.release != null }
                compose.onNodeWithText("下载安装包", substring = true).performClick()
                compose.waitUntil(15_000) { controller.state.value.downloading && connected.count == 0L }
                compose.onNodeWithText("取消下载").assertIsDisplayed().performClick()
                compose.waitUntil(10_000) { !controller.state.value.downloading }
                compose.onNodeWithText("下载已取消").assertExists()
            } finally {
                runBlocking { controller.cancel() }
                finish.countDown()
                server.close()
                delayWorker.join(1_000)
                context.deleteSharedPreferences(name)
            }
        }
    }
}
