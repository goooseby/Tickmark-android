package io.github.goooseby.tickmark

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.goooseby.tickmark.app.MainActivity
import io.github.goooseby.tickmark.app.TickmarkApp
import io.github.goooseby.tickmark.widget.AwayWidgetReceiver
import io.github.goooseby.tickmark.widget.TodayWidgetReceiver
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class PackageIdentityTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext

    @Suppress("DEPRECATION")
    @Test fun installedApplicationAndWidgetComponentsUseTheNewIdentity() {
        assertEquals("io.github.goooseby.tickmark",context.packageName)
        assertEquals(context.packageName,BuildConfig.APPLICATION_ID)
        assertEquals(TickmarkApp::class.java.name,context.applicationInfo.className)
        val manager=context.packageManager
        assertEquals(context.packageName,manager.getActivityInfo(ComponentName(context,MainActivity::class.java),0).packageName)
        for(type in listOf(TodayWidgetReceiver::class.java,AwayWidgetReceiver::class.java)) {
            val receiver=manager.getReceiverInfo(ComponentName(context,type),PackageManager.GET_META_DATA)
            assertEquals(type.name,receiver.name)
            assertTrue(receiver.metaData.getInt("android.appwidget.provider")!=0)
        }
    }

    @Test fun updateFileProviderResolvesAndReadsUnderTheNewAuthority() {
        val file=File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),"updates/identity-${UUID.randomUUID()}.apk")
        file.parentFile!!.mkdirs()
        try {
            file.writeBytes(byteArrayOf(1,2,3))
            val uri=FileProvider.getUriForFile(context,"${context.packageName}.updates",file)
            assertEquals("io.github.goooseby.tickmark.updates",uri.authority)
            context.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(file.readBytes(),it.readBytes()) }
        } finally { file.delete() }
    }
}
