package io.github.goooseby.tickmark.engine

import io.github.goooseby.tickmark.engine.analytics.*
import io.github.goooseby.tickmark.engine.model.Session
import org.junit.Assert.*
import org.junit.Test

class CategoryEngineTest {
    @Test fun groupsApplicationsByPlatformCategory() {
        val result=CategoryEngine.totals(listOf(Session("bili",0,100),Session("music",100,160),Session("chat",160,200)),
            mapOf("bili" to AppCategory.MEDIA,"music" to AppCategory.MEDIA,"chat" to AppCategory.SOCIAL),0,200)
        assertEquals(listOf(AppCategory.MEDIA to 160L,AppCategory.SOCIAL to 40L),result)
    }
    @Test fun clipsAndDeduplicatesTheSameApplicationBeforeGrouping() {
        val result=CategoryEngine.totals(listOf(Session("bili",0,100),Session("bili",50,150)),mapOf("bili" to AppCategory.MEDIA),25,125)
        assertEquals(listOf(AppCategory.MEDIA to 100L),result)
    }
    @Test fun concurrentApplicationsUseTheSameBasisAsApplicationComposition() {
        val result=CategoryEngine.totals(listOf(Session("video",0,100),Session("music",0,100)),mapOf("video" to AppCategory.MEDIA,"music" to AppCategory.MEDIA),0,100)
        assertEquals(200L,result.single().second)
    }
    @Test fun missingCategoryStaysUnclassifiedIncludingInferredSessions() {
        assertEquals(listOf(AppCategory.UNKNOWN to 100L),CategoryEngine.totals(listOf(Session("unknown",0,100,true)),emptyMap(),0,100))
        assertEquals(AppCategory.UNKNOWN,AppCategory.parse("unsupported"))
        assertEquals(emptyList<Pair<AppCategory,Long>>(),CategoryEngine.totals(emptyList(),emptyMap(),0,100))
    }
}
