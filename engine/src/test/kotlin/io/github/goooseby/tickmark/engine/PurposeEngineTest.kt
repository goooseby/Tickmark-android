package io.github.goooseby.tickmark.engine

import io.github.goooseby.tickmark.engine.analytics.*
import io.github.goooseby.tickmark.engine.model.*
import org.junit.Assert.*
import org.junit.Test

class PurposeEngineTest {
    @Test fun manualOverridesDefaultsAndPreservesDeviceTotal() {
        val totals=PurposeEngine.totals(listOf(Session("a",0,100)),listOf(0L to 100L),listOf(PurposeRange(20,60,"学习")),mapOf("a" to "娱乐"),0,100).toMap()
        assertEquals(40L,totals["学习"]); assertEquals(60L,totals["娱乐"]); assertEquals(100L,totals.values.sum())
    }
    @Test fun concurrentDifferentPurposesHaveOneExplicitBucket() {
        val totals=PurposeEngine.totals(listOf(Session("a",0,100),Session("b",50,100)),listOf(0L to 100L),emptyList(),mapOf("a" to "学习","b" to "娱乐"),0,100).toMap()
        assertEquals(50L,totals["学习"]); assertEquals(50L,totals["并发用途"])
    }
    @Test fun duplicatedIntervalsAndUnknownDeviceDoNotInflatePurposes() {
        val totals=PurposeEngine.totals(listOf(Session("a",0,200)),listOf(0L to 100L,0L to 100L),emptyList(),emptyMap(),50,200)
        assertEquals(listOf("未分类" to 50L),totals)
    }
    @Test fun numericVersionsHandleMultiDigitVersionsAndRejectPreviews() {
        assertTrue(AppVersion.parse("v0.10.0")!!>AppVersion.parse("0.9.8")!!)
        assertEquals(AppVersion.parse("v1.2.3"),AppVersion.parse("1.2.3"))
        assertNull(AppVersion.parse("v1.2.3-beta")); assertNull(AppVersion.parse("garbage"))
    }
}
