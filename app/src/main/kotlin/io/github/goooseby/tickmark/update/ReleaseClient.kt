package io.github.goooseby.tickmark.update

import io.github.goooseby.tickmark.engine.model.AppVersion
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

const val REPOSITORY_URL="https://github.com/goooseby/Tickmark-android"
private const val RELEASE_PREFIX="$REPOSITORY_URL/releases/"
data class ReleaseInfo(val tag:String,val title:String,val notes:String,val page:String,val apk:String?,val digest:String?,val bytes:Long) {
    fun json():String = JSONObject().put("tag",tag).put("title",title).put("notes",notes).put("page",page).put("apk",apk).put("digest",digest).put("bytes",bytes).toString()
    companion object {
        fun restore(text:String):ReleaseInfo { val j=JSONObject(text); return ReleaseInfo(j.getString("tag"),j.getString("title"),j.getString("notes"),j.getString("page"),j.optString("apk").takeIf { it.startsWith(RELEASE_PREFIX) },j.optString("digest").takeIf { Regex("^[a-fA-F0-9]{64}$").matches(it) },j.getLong("bytes")) }
    }
}
open class ReleaseClient(private val endpoint:String="https://api.github.com/repos/goooseby/Tickmark-android/releases/latest") {
    open fun latest():ReleaseInfo? {
        val connection=URL(endpoint).openConnection() as HttpURLConnection
        connection.connectTimeout=15_000; connection.readTimeout=20_000
        connection.setRequestProperty("Accept","application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version","2022-11-28")
        connection.setRequestProperty("User-Agent","Tickmark-Android")
        try {
            when(connection.responseCode) {
                404->return null
                403,429->error("GitHub 请求过于频繁，请稍后重试")
                in 200..299->Unit
                else->error("GitHub 暂时不可用（${connection.responseCode}）")
            }
            val text=connection.inputStream.bufferedReader().use { it.readTextLimited(1_000_000) }
            return parse(text)
        } finally { connection.disconnect() }
    }
    companion object {
        fun parse(text:String):ReleaseInfo {
            val j=JSONObject(text)
            require(!j.optBoolean("draft") && !j.optBoolean("prerelease")) { "此版本尚未正式发布" }
            val tag=j.getString("tag_name")
            require(AppVersion.parse(tag)!=null) { "发布版本号格式不受支持" }
            val page=j.getString("html_url")
            require(page.startsWith(RELEASE_PREFIX) && URI(page).host=="github.com") { "更新来源无效" }
            val assets=j.getJSONArray("assets")
            val candidates=(0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.getString("name")=="tickmark-$tag.apk" }
            val asset=candidates.singleOrNull()
            val url=asset?.getString("browser_download_url")?.takeIf { it.startsWith("${RELEASE_PREFIX}download/$tag/") && URI(it).host=="github.com" }
            val digest=asset?.optString("digest")?.removePrefix("sha256:")?.takeIf { Regex("^[a-fA-F0-9]{64}$").matches(it) }
            val bytes=asset?.optLong("size") ?: 0
            require(bytes in 0..250_000_000L) { "更新文件大小异常" }
            return ReleaseInfo(tag,j.optString("name").ifBlank { tag },j.optString("body").take(20_000),page,url,digest,bytes)
        }
    }
}
private fun java.io.Reader.readTextLimited(max:Int):String {
    val output=StringBuilder(); val buffer=CharArray(8192)
    while(true) { val n=read(buffer); if(n<0) break; require(output.length+n<=max) { "更新响应过大" }; output.append(buffer,0,n) }
    return output.toString()
}
