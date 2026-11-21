package sh.belfry.core.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Is there a newer Belfry for Android? (Xavier, #issues 2026-11-13: "Add a way to auto update the
 * android app (check if there is a newer apk)".) The releases of opencollective/belfry-android on
 * GitHub (release.yml attaches belfry-<version>.apk and its .sha256 to each tag, as
 * pre-releases). Only that repository's download links are followed; versions compare as
 * numbers (0.4.10 > 0.4.9); drafts are skipped. Nothing from a release's text is shown: the app
 * says only the version number.
 */
data class Release(val version: String, val apkUrl: String, val sha256Url: String?)

class RateLimited : Exception("GitHub asked to wait")

/**
 * What a tap on Update does (OSV, 2026-11-21: Update opened Android's app info with no word of
 * why). [INSTALL]: the app may install packages, so it does. [ASK]: Android's "Install unknown
 * apps" is off for Belfry: a sheet says why, then opens that setting; back in the app, the update
 * goes on by itself. [ASK_AGAIN]: the person came back from the setting and it is still off (or the
 * device showed some other screen): the sheet offers belfry.sh/android first. [WEB]: the device has
 * no such setting: belfry.sh/android opens in the browser.
 */
enum class InstallStep { INSTALL, ASK, ASK_AGAIN, WEB }

object AppUpdate {
    const val REPO = "opencollective/belfry-android"
    const val API = "https://api.github.com/repos/$REPO/releases?per_page=10"
    const val DOWNLOADS = "https://github.com/$REPO/releases/download/"
    /** Where the APK can always be downloaded by hand (belfry-agent's page follows the newest release). */
    const val WEB = "https://belfry.sh/android"
    /** How long a trip to the setting counts as "for this update" (the app may be killed meanwhile). */
    const val ASKED_FOR_MS = 30 * 60_000L
    private val VERSION = Regex("""^v?(\d{1,4})\.(\d{1,4})\.(\d{1,4})$""")

    /** [major, minor, patch], or null for anything else. */
    fun parts(v: String): List<Int>? = VERSION.matchEntire(v.trim())?.groupValues?.drop(1)?.map { it.toInt() }

    /** a < b → negative; an unreadable version is older than any readable one. */
    fun compare(a: String, b: String): Int {
        val x = parts(a) ?: return if (parts(b) == null) 0 else -1
        val y = parts(b) ?: return 1
        for (i in 0..2) if (x[i] != y[i]) return x[i].compareTo(y[i])
        return 0
    }

    fun isNewer(latest: String, current: String) = compare(latest, current) > 0

    /** The newest release in GitHub's answer that has its APK, or null. */
    fun latest(json: String): Release? {
        val list = try { Json.parseToJsonElement(json) as? JsonArray } catch (e: Exception) { null } ?: return null
        return list.mapNotNull { el ->
            val r = el as? JsonObject ?: return@mapNotNull null
            if ((r["draft"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
            val tag = (r["tag_name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val p = parts(tag) ?: return@mapNotNull null
            val version = p.joinToString(".")
            val assets = (r["assets"] as? JsonArray).orEmpty().mapNotNull { a ->
                val o = a as? JsonObject ?: return@mapNotNull null
                val name = (o["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val url = (o["browser_download_url"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                if (url.startsWith(DOWNLOADS) && url.endsWith("/$name")) name to url else null
            }.toMap()
            val apk = assets["belfry-$version.apk"] ?: return@mapNotNull null
            Release(version, apk, assets["belfry-$version.apk.sha256"])
        }.maxWithOrNull { a, b -> compare(a.version, b.version) }
    }

    /** Asks GitHub (blocking: call it off the main thread). 403 / 429 → [RateLimited]. */
    fun fetch(client: OkHttpClient): Release? {
        val req = Request.Builder().url(API).header("Accept", "application/vnd.github+json").header("User-Agent", "belfry-android").build()
        client.newCall(req).execute().use { res ->
            if (res.code == 403 || res.code == 429) throw RateLimited()
            if (!res.isSuccessful) return null
            return latest(res.body?.string() ?: return null)
        }
    }

    /** The hash in a release's .sha256 file ("<hex>  belfry-x.y.z.apk"), or null. */
    fun sha256Of(text: String): String? = Regex("""^([0-9a-fA-F]{64})\b""").find(text.trim())?.groupValues?.get(1)?.lowercase()

    /**
     * [canInstall]: Android lets this app install packages. [hasSetting]: the device opened (or can
     * open) the setting. [askedAtMs]: when the person was last sent to it for this update (0: never).
     */
    fun installStep(canInstall: Boolean, hasSetting: Boolean, askedAtMs: Long, nowMs: Long): InstallStep = when {
        canInstall -> InstallStep.INSTALL
        !hasSetting -> InstallStep.WEB
        askedAtMs > 0 && nowMs - askedAtMs in 0..ASKED_FOR_MS -> InstallStep.ASK_AGAIN
        else -> InstallStep.ASK
    }

    /** A pending update kept across the trip to the setting: "version apk sha256-or-dash". */
    fun pendingOf(r: Release): String = listOf(r.version, r.apkUrl, r.sha256Url ?: "-").joinToString(" ")

    /** Read back with the same checks as GitHub's answer (this repository's downloads only). */
    fun pendingFrom(s: String?): Release? {
        val p = s?.trim()?.split(" ") ?: return null
        if (p.size != 3) return null
        val v = parts(p[0])?.joinToString(".") ?: return null
        val apk = p[1].takeIf { it.startsWith(DOWNLOADS) && it.endsWith("/belfry-$v.apk") } ?: return null
        val sha = p[2].takeIf { it != "-" }?.also { if (!it.startsWith(DOWNLOADS) || !it.endsWith("/belfry-$v.apk.sha256")) return null }
        return Release(v, apk, sha)
    }
}
