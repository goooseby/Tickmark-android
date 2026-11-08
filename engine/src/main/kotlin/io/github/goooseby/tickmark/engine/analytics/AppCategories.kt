package io.github.goooseby.tickmark.engine.analytics

import io.github.goooseby.tickmark.engine.model.Session

enum class AppCategory(val title:String) {
    MEDIA("影音娱乐"), SOCIAL("社交通讯"), GAME("游戏"), EDUCATION("学习教育"),
    PRODUCTIVITY("效率办公"), LIFE("生活服务"), NEWS("资讯阅读"), MAPS("出行导航"),
    TOOLS("工具"), OTHER("其他"), UNKNOWN("未分类");

    companion object {
        fun parse(value:String?):AppCategory=entries.find{it.name==value} ?: UNKNOWN
    }
}

/** Same foreground-time basis as application composition; no inference about user intent. */
object CategoryEngine {
    fun totals(sessions:List<Session>,categories:Map<String,AppCategory>,from:Long,to:Long):List<Pair<AppCategory,Long>> =
        ReportEngine.composition(sessions,from,to).groupBy{categories[it.first] ?: AppCategory.UNKNOWN}
            .map{(category,apps)->category to apps.sumOf{it.second}}.sortedByDescending{it.second}
}
