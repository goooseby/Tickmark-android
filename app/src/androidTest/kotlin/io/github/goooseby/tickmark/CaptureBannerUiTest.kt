package io.github.goooseby.tickmark

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.goooseby.tickmark.data.ConfigRow
import io.github.goooseby.tickmark.data.TickDatabase
import io.github.goooseby.tickmark.data.TickRepository
import io.github.goooseby.tickmark.ui.Tickmark
import io.github.goooseby.tickmark.ui.TickmarkTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** 首页只在后台长期没有补采时提示；使用内存数据库，不触碰正式记录。 */
class CaptureBannerUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun homeShowsHintWhenBackgroundCaptureIsStale() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, TickDatabase::class.java).build()
        val repo = TickRepository(context, db, captureEnabled = false)
        assumeTrue(repo.hasAccess())
        runBlocking {
            repo.dao.putConfig(
                listOf(
                    ConfigRow("paused", "false"),
                    ConfigRow("lastSync", System.currentTimeMillis().toString()),
                    ConfigRow("lastBackgroundCapture", (System.currentTimeMillis() - 13 * 3_600_000L).toString()),
                ),
            )
        }
        compose.setContent { TickmarkTheme(false) { Tickmark(repo) } }

        compose.onNodeWithText("超过 12 小时没有后台补采 · 检查设置").assertExists()
    }
}
