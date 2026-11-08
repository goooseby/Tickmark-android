package io.github.goooseby.tickmark.data

import android.content.Context
import android.content.pm.ApplicationInfo
import io.github.goooseby.tickmark.engine.analytics.AppCategory
import org.json.JSONObject

/** Explicit package rules fill gaps in Android metadata; names and activity content are not guessed. */
class AppClassifier(context:Context) {
    private val packages:Map<String,AppCategory> = context.assets.open("app-categories.json").bufferedReader().use { reader ->
        val rules=JSONObject(reader.readText())
        buildMap {
            for(category in rules.keys()) {
                val resolved=AppCategory.valueOf(category)
                val names=rules.getJSONArray(category)
                for(i in 0 until names.length()) {
                    val pkg=names.getString(i)
                    require(!containsKey(pkg)){"Duplicate category rule: $pkg"}
                    put(pkg,resolved)
                }
            }
        }
    }
    fun classify(pkg:String,systemCategory:Int=ApplicationInfo.CATEGORY_UNDEFINED):AppCategory =
        packages[pkg] ?: when(systemCategory) {
            ApplicationInfo.CATEGORY_GAME->AppCategory.GAME
            ApplicationInfo.CATEGORY_AUDIO,ApplicationInfo.CATEGORY_VIDEO->AppCategory.MEDIA
            ApplicationInfo.CATEGORY_SOCIAL->AppCategory.SOCIAL
            ApplicationInfo.CATEGORY_NEWS->AppCategory.NEWS
            ApplicationInfo.CATEGORY_MAPS->AppCategory.MAPS
            ApplicationInfo.CATEGORY_PRODUCTIVITY->AppCategory.PRODUCTIVITY
            ApplicationInfo.CATEGORY_IMAGE->AppCategory.TOOLS
            else->AppCategory.UNKNOWN
        }
}

val AppRow.appCategory:AppCategory get()=AppCategory.parse(categoryOverride ?: automaticCategory)
