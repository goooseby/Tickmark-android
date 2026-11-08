package io.github.goooseby.tickmark

import androidx.test.platform.app.InstrumentationRegistry
import io.github.goooseby.tickmark.update.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class UpdateTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun release()=ReleaseInfo("v99.0.0","Test","notes","$REPOSITORY_URL/releases/tag/v99.0.0","$REPOSITORY_URL/releases/download/v99.0.0/tickmark-v99.0.0.apk","a".repeat(64),10)
    private fun response()="""{"tag_name":"v99.0.0","html_url":"$REPOSITORY_URL/releases/tag/v99.0.0","name":"Test","body":"notes","draft":false,"prerelease":false,"assets":[{"name":"tickmark-v99.0.0.apk","browser_download_url":"$REPOSITORY_URL/releases/download/v99.0.0/tickmark-v99.0.0.apk","digest":"sha256:${"a".repeat(64)}","size":10}]}"""
    @Test fun parserRestrictsSourceAndRequiresDigestForDirectDownload() {
        assertEquals(release(),ReleaseClient.parse(response()))
        val bad=JSONObject(response()).put("html_url","https://example.com/releases/tag/v99.0.0")
        try {ReleaseClient.parse(bad.toString());fail("foreign source")} catch(_:IllegalArgumentException) { }
        val noDigest=JSONObject(response());noDigest.getJSONArray("assets").getJSONObject(0).remove("digest")
        assertNull(ReleaseClient.parse(noDigest.toString()).digest)
        try {ReleaseClient.parse(JSONObject(response()).put("prerelease",true).toString());fail("preview")} catch(_:IllegalArgumentException) { }
    }
    @Test fun noReleaseAndOfflineKeepDistinctStatesAndCachedVersion() = runBlocking {
        var result:ReleaseInfo?=null;var offline=false
        val client=object:ReleaseClient() {override fun latest():ReleaseInfo? {if(offline) throw java.io.IOException("offline");return result}}
        val name="update-test-${UUID.randomUUID()}"
        try {
            val updates=UpdateController(context,client,name)
            updates.check();assertEquals("尚无正式发布版本",updates.state.value.message)
            result=release();updates.check();assertNotNull(updates.state.value.release)
            offline=true;updates.check();assertNotNull(updates.state.value.release);assertTrue(updates.state.value.message!!.contains("网络"))
            val restored=UpdateController(context,client,name)
            assertEquals(release(),restored.state.value.release)
        } finally {context.getSharedPreferences(name,0).edit().clear().commit()}
    }
    @Test fun corruptAndAlreadyInstalledApksAreRejectedBeforeInstaller() {
        val controller=UpdateController(context,preferencesName="validation-${UUID.randomUUID()}")
        val installed=File(context.applicationInfo.sourceDir)
        val hash=MessageDigest.getInstance("SHA-256").digest(installed.readBytes()).joinToString(""){"%02x".format(it)}
        val validBytes=release().copy(tag="v${BuildConfig.VERSION_NAME}",bytes=installed.length(),digest=hash)
        try {controller.verifyFile(installed,validBytes.copy(digest="0".repeat(64)));fail("wrong hash")} catch(e:IllegalArgumentException) {assertTrue(e.message!!.contains("校验"))}
        try {controller.verifyFile(installed,validBytes);fail("already installed")} catch(e:IllegalArgumentException) {assertTrue(e.message!!.contains("版本"))}
    }
}
