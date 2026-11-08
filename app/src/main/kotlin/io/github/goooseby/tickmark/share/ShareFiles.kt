package io.github.goooseby.tickmark.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

class ShareFileProvider:FileProvider()

/** Files stay readable after the chooser closes; every share owns an immutable file. */
object ShareFiles {
    const val PROJECT_URL="https://github.com/goooseby/Tickmark-android"
    private const val RETENTION=7L*24*60*60*1000

    private fun destination(context:Context,name:String):File {
        require(name.matches(Regex("[A-Za-z0-9._-]+")))
        val root=File(context.cacheDir,"shared").apply{mkdirs()}
        val cutoff=System.currentTimeMillis()-RETENTION
        root.listFiles()?.filter{it.isDirectory && it.lastModified()<cutoff}?.forEach{it.deleteRecursively()}
        return File(File(root,UUID.randomUUID().toString()).apply{mkdirs()},name)
    }

    fun text(context:Context,name:String,text:String):Uri = uri(context,destination(context,name).apply{writeText(text,Charsets.UTF_8)})

    fun image(context:Context,name:String,bitmap:Bitmap):Uri {
        val file=destination(context,name)
        file.outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)){"分享图片生成失败"}}
        return uri(context,file)
    }

    private fun uri(context:Context,file:File)=FileProvider.getUriForFile(context,"${context.packageName}.share",file)

    fun intent(uri:Uri,mime:String,title:String,text:String?=null):Intent = Intent(Intent.ACTION_SEND).apply {
        type=mime
        putExtra(Intent.EXTRA_STREAM,uri)
        putExtra(Intent.EXTRA_TITLE,title)
        if(text!=null) putExtra(Intent.EXTRA_TEXT,text)
        clipData=ClipData.newRawUri(title,uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun launch(context:Context,intent:Intent) {
        context.startActivity(Intent.createChooser(intent,"分享").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
