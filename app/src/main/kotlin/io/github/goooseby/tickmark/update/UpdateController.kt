package io.github.goooseby.tickmark.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.goooseby.tickmark.BuildConfig
import io.github.goooseby.tickmark.engine.model.AppVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class UpdateState(val checking:Boolean=false,val release:ReleaseInfo?=null,val message:String?=null,val progress:Int?=null,val ready:Boolean=false,val downloading:Boolean=false,val lastCheck:Long=0,val autoCheck:Boolean=false)
class UpdateController(private val context:Context,private val client:ReleaseClient=ReleaseClient(),preferencesName:String="updates") {
    private val prefs=context.getSharedPreferences(preferencesName,Context.MODE_PRIVATE)
    private val manager=context.getSystemService(DownloadManager::class.java)
    private val mutex=Mutex()
    private val mutable=MutableStateFlow(UpdateState(release=runCatching { ReleaseInfo.restore(prefs.getString("release",null)!!) }.getOrNull(),lastCheck=prefs.getLong("lastCheck",0),autoCheck=prefs.getBoolean("auto",false)))
    val state=mutable.asStateFlow()
    private fun file()=File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),"updates/tickmark-update.apk")
    fun setAuto(enabled:Boolean) { prefs.edit().putBoolean("auto",enabled).apply(); mutable.value=mutable.value.copy(autoCheck=enabled) }
    suspend fun check(automatic:Boolean=false) = withContext(Dispatchers.IO) {
        if(!mutex.tryLock()) return@withContext
        try {
            if(automatic && (!state.value.autoCheck || state.value.downloading || state.value.ready || System.currentTimeMillis()-prefs.getLong("lastAttempt",0)<86_400_000L)) return@withContext
            prefs.edit().putLong("lastAttempt",System.currentTimeMillis()).apply()
            mutable.value=state.value.copy(checking=true,message=null)
            val release=client.latest()
            val now=System.currentTimeMillis()
            val newer=release?.takeIf { AppVersion.parse(it.tag)!!>AppVersion.parse(BuildConfig.VERSION_NAME)!! }
            prefs.edit().putLong("lastCheck",now).apply()
            if(!state.value.downloading && !state.value.ready) {
                if(newer!=null) prefs.edit().putString("release",newer.json()).apply() else prefs.edit().remove("release").apply()
            }
            mutable.value=state.value.copy(checking=false,lastCheck=now,release=if(state.value.downloading || state.value.ready) state.value.release else newer,message=when { release==null->"尚无正式发布版本"; newer==null->"已是最新版本"; else->"发现新版本 ${newer.tag}" })
        } catch(e:Exception) {
            if(e is kotlinx.coroutines.CancellationException) throw e
            mutable.value=state.value.copy(checking=false,message=if(e is java.io.IOException) "无法连接 GitHub，请检查网络后重试" else e.message ?: "检查更新失败")
        } finally { mutable.value=state.value.copy(checking=false); mutex.unlock() }
    }
    suspend fun download() = withContext(Dispatchers.IO) { mutex.withLock {
        try {
            val release=state.value.release ?: error("请先检查更新")
            require(release.apk!=null && release.digest!=null) { "此版本缺少安装包或校验信息，请前往发布页" }
            cancelInternal()
            val target=file(); target.parentFile?.mkdirs(); target.delete()
            val request=DownloadManager.Request(Uri.parse(release.apk)).setTitle("Tickmark ${release.tag}")
                .setDescription("正在下载应用更新").setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(target)).setAllowedOverMetered(true).setAllowedOverRoaming(false)
            val id=manager.enqueue(request)
            prefs.edit().putLong("downloadId",id).putString("release",release.json()).apply()
            mutable.value=state.value.copy(downloading=true,progress=0,ready=false,message="正在下载，可离开此页面")
        } catch(e:Exception) { mutable.value=state.value.copy(message=e.message ?: "无法开始下载",downloading=false) }
    } }
    suspend fun refresh() = withContext(Dispatchers.IO) { mutex.withLock {
        val id=prefs.getLong("downloadId",-1)
        if(id<0) return@withLock
        try {
            manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if(!cursor.moveToFirst()) { cancelInternal(); mutable.value=state.value.copy(downloading=false,ready=false,message="下载已移除，可重新下载"); return@withLock }
                fun number(name:String)=cursor.getLong(cursor.getColumnIndexOrThrow(name))
                when(number(DownloadManager.COLUMN_STATUS).toInt()) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        if(!state.value.ready) verify()
                        mutable.value=state.value.copy(ready=true,downloading=false,progress=100,message="下载完成，已校验安装包")
                    }
                    DownloadManager.STATUS_FAILED -> { cancelInternal(); mutable.value=state.value.copy(downloading=false,ready=false,message="下载失败，请重试或前往发布页") }
                    else -> {
                        val total=number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        val downloaded=number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        mutable.value=state.value.copy(downloading=true,progress=if(total>0) (downloaded*100/total).toInt().coerceIn(0,100) else null,message=if(number(DownloadManager.COLUMN_STATUS).toInt()==DownloadManager.STATUS_PAUSED) "等待网络，联网后继续下载" else "正在下载，可离开此页面")
                    }
                }
            }
        } catch(e:Exception) { cancelInternal(); file().delete(); mutable.value=state.value.copy(ready=false,downloading=false,message=e.message ?: "安装包校验失败，请重新下载") }
    } }
    private fun verify() {
        val release=state.value.release ?: error("缺少更新信息，请重新检查")
        verifyFile(file(),release)
    }
    internal fun verifyFile(apk:File,release:ReleaseInfo) {
        require(apk.isFile && apk.length()==release.bytes) { "安装包不完整，请重新下载" }
        val hash=MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input -> val buffer=ByteArray(64*1024); while(true) { val n=input.read(buffer); if(n<0) break; hash.update(buffer,0,n) } }
        require(hash.digest().joinToString("") { "%02x".format(it) }.equals(release.digest,true)) { "安装包校验失败，请重新下载" }
        @Suppress("DEPRECATION") val archive=context.packageManager.getPackageArchiveInfo(apk.absolutePath,PackageManager.GET_SIGNING_CERTIFICATES) ?: error("无法读取安装包")
        require(archive.packageName==context.packageName) { "安装包不属于 Tickmark" }
        require(archive.longVersionCode>BuildConfig.VERSION_CODE && archive.versionName==release.tag.removePrefix("v")) { "安装包版本不匹配或已安装" }
        @Suppress("DEPRECATION") val installed=context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
        val current=installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        val incoming=archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        require(current.isNotEmpty() && current==incoming) { "安装包签名与当前应用不同，请使用同一来源的正式版本" }
    }
    suspend fun install() = withContext(Dispatchers.IO) {
        try {
            mutex.withLock { verify() }
            if(!context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                mutable.value=state.value.copy(message="允许安装后返回，再点击安装更新")
            } else {
                val uri=FileProvider.getUriForFile(context,"${context.packageName}.updates",file())
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch(e:Exception) { mutable.value=state.value.copy(message=e.message ?: "无法打开安装程序") }
    }
    private fun cancelInternal() { val id=prefs.getLong("downloadId",-1); if(id>=0) manager.remove(id); prefs.edit().remove("downloadId").apply() }
    suspend fun cancel() = withContext(Dispatchers.IO) { mutex.withLock { cancelInternal(); file().delete(); mutable.value=state.value.copy(downloading=false,ready=false,progress=null,message="下载已取消") } }
}
