package sh.belfry.core.update

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class AppUpdateTest {
    private fun release(tag: String, draft: Boolean = false, url: String? = null) = """
        {"tag_name":"$tag","draft":$draft,"prerelease":true,"body":"<b>ignored</b>","assets":[
          {"name":"belfry-${tag.removePrefix("v")}.apk","browser_download_url":"${url ?: "https://github.com/opencollective/belfry-android/releases/download/$tag/belfry-${tag.removePrefix("v")}.apk"}"},
          {"name":"belfry-${tag.removePrefix("v")}.apk.sha256","browser_download_url":"https://github.com/opencollective/belfry-android/releases/download/$tag/belfry-${tag.removePrefix("v")}.apk.sha256"}]}
    """.trimIndent()

    @Test fun versionsCompareAsNumbers() {
        assertTrue(AppUpdate.isNewer("0.4.10", "0.4.9"))
        assertTrue(AppUpdate.isNewer("v1.0.0", "0.99.99"))
        assertFalse(AppUpdate.isNewer("0.4.2", "0.4.2"))
        assertFalse(AppUpdate.isNewer("nonsense", "0.1.0"))
    }

    @Test fun theNewestReleaseWithItsApk() {
        val json = "[${release("v0.4.9")},${release("v0.4.10")},${release("v0.5.0", draft = true)}]"
        val r = AppUpdate.latest(json)!!
        assertEquals("0.4.10", r.version)
        assertTrue(r.apkUrl.endsWith("/v0.4.10/belfry-0.4.10.apk"))
        assertTrue(r.sha256Url!!.endsWith(".apk.sha256"))
    }

    @Test fun onlyThisRepositorysDownloads() {
        val json = "[${release("v0.9.0", url = "https://evil.example/belfry-0.9.0.apk")},${release("v0.4.2")}]"
        assertEquals("0.4.2", AppUpdate.latest(json)!!.version)
        assertNull(AppUpdate.latest("{\"message\":\"API rate limit exceeded\"}"))
        assertNull(AppUpdate.latest("not json"))
    }

    @Test fun theHashFile() {
        val h = "a".repeat(64)
        assertEquals(h, AppUpdate.sha256Of("$h  belfry-0.4.3.apk\n"))
        assertNull(AppUpdate.sha256Of("nope"))
    }

    @Test fun whatUpdateDoes() {
        val now = 10 * 3_600_000L
        assertEquals(InstallStep.INSTALL, AppUpdate.installStep(canInstall = true, hasSetting = true, askedAtMs = 0, nowMs = now))
        assertEquals(InstallStep.INSTALL, AppUpdate.installStep(canInstall = true, hasSetting = false, askedAtMs = now - 1000, nowMs = now))
        assertEquals(InstallStep.ASK, AppUpdate.installStep(canInstall = false, hasSetting = true, askedAtMs = 0, nowMs = now))
        // back from the setting, still off: belfry.sh is offered first
        assertEquals(InstallStep.ASK_AGAIN, AppUpdate.installStep(canInstall = false, hasSetting = true, askedAtMs = now - 60_000, nowMs = now))
        // a trip long ago counts no more
        assertEquals(InstallStep.ASK, AppUpdate.installStep(canInstall = false, hasSetting = true, askedAtMs = now - AppUpdate.ASKED_FOR_MS - 1, nowMs = now))
        assertEquals(InstallStep.WEB, AppUpdate.installStep(canInstall = false, hasSetting = false, askedAtMs = 0, nowMs = now))
        assertEquals("https://belfry.sh/android", AppUpdate.WEB)
    }

    @Test fun aPendingUpdateComesBackChecked() {
        val r = AppUpdate.latest("[${release("v0.7.4")}]")!!
        assertEquals(r, AppUpdate.pendingFrom(AppUpdate.pendingOf(r)))
        assertEquals(r.copy(sha256Url = null), AppUpdate.pendingFrom(AppUpdate.pendingOf(r.copy(sha256Url = null))))
        assertNull(AppUpdate.pendingFrom(null))
        assertNull(AppUpdate.pendingFrom("0.7.4 https://evil.example/belfry-0.7.4.apk -"))
        assertNull(AppUpdate.pendingFrom("0.7.4 ${r.apkUrl.replace("0.7.4.apk", "0.7.5.apk")} -"))
        assertNull(AppUpdate.pendingFrom("0.7.4 ${r.apkUrl} https://evil.example/x.sha256"))
        assertNull(AppUpdate.pendingFrom("garbage"))
    }
}
