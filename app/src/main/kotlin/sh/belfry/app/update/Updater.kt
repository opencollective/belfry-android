package sh.belfry.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import sh.belfry.app.BuildConfig
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import sh.belfry.core.update.AppUpdate
import sh.belfry.core.update.RateLimited
import sh.belfry.core.update.Release
import java.io.File
import java.security.MessageDigest

/**
 * Belfry for Android updates itself (#issues 2026-11-13). [check] asks GitHub at most once an
 * hour (on launch, back in the foreground), a 403/429 waits six hours; [install] downloads the
 * APK, checks it against the release's .sha256 (that only catches a broken download: what makes
 * an update safe is Android itself, which installs it only when it is signed with the same key),
 * and hands it to the system's installer, which asks the person to confirm. Release builds only.
 */
object Updater {
    sealed interface State {
        data object Idle : State
        data object Downloading : State
        data object Installing : State
        data class Failed(val message: String) : State
    }

    private const val PREFS = "app-update"
    private const val K_AUTO = "auto"
    private const val K_NEXT = "next-check"
    private const val K_HIDDEN = "hidden"
    private const val HOUR = 3_600_000L

    /** Plain HTTP with timeouts (the relays' client waits forever on a read). */
    private val http: OkHttpClient by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build() }

    private val _available = MutableStateFlow<Release?>(null)
    /** A newer version than this one, when there is one. */
    val available: StateFlow<Release?> = _available
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** Debug and test builds (version 0.1.0) never ask. */
    val enabled: Boolean get() = !BuildConfig.DEBUG && AppUpdate.parts(BuildConfig.VERSION_NAME) != null

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun auto(c: Context): Boolean = prefs(c).getBoolean(K_AUTO, true)
    fun setAuto(c: Context, on: Boolean) { prefs(c).edit().putBoolean(K_AUTO, on).apply() }

    /** The bar was closed for this version: it comes back for the next one (Me keeps the row). */
    fun hiddenFor(c: Context): String = prefs(c).getString(K_HIDDEN, "") ?: ""
    fun hide(c: Context, version: String) { prefs(c).edit().putString(K_HIDDEN, version).apply() }

    /** Ask GitHub, unless it was asked less than an hour ago (FORCE: Me's "Check now"). */
    suspend fun check(c: Context, force: Boolean = false): Release? {
        if (!enabled || (!force && !auto(c))) return _available.value
        val now = System.currentTimeMillis()
        if (!force && now < prefs(c).getLong(K_NEXT, 0)) return _available.value
        prefs(c).edit().putLong(K_NEXT, now + HOUR).apply()
        val latest = try {
            withContext(Dispatchers.IO) { AppUpdate.fetch(http) }
        } catch (e: RateLimited) {
            prefs(c).edit().putLong(K_NEXT, now + 6 * HOUR).apply(); return _available.value
        } catch (e: Exception) {
            return _available.value
        }
        _available.value = latest?.takeIf { AppUpdate.isNewer(it.version, BuildConfig.VERSION_NAME) }
        return _available.value
    }

    /** Download RELEASE and open the system's install screen. */
    suspend fun install(c: Context, release: Release) {
        val pm = c.packageManager
        if (Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
            // once: let Belfry install updates (Settings → Install unknown apps)
            c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        _state.value = State.Downloading
        try {
            val file = withContext(Dispatchers.IO) { download(c, release) }
            _state.value = State.Installing
            withContext(Dispatchers.IO) { commit(c, file) }
        } catch (e: Exception) {
            _state.value = State.Failed(if (e is BrokenDownload) "The download was broken. Try again." else "Couldn't download the update. Try again later.")
        }
    }

    private class BrokenDownload : Exception()

    private fun download(c: Context, r: Release): File {
        val client = http
        val want = r.sha256Url?.let { url ->
            client.newCall(Request.Builder().url(url).build()).execute().use { res -> if (res.isSuccessful) AppUpdate.sha256Of(res.body?.string() ?: "") else null }
        }
        val dir = File(c.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "belfry-${r.version}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(Request.Builder().url(r.apkUrl).build()).execute().use { res ->
            if (!res.isSuccessful) throw java.io.IOException("HTTP ${res.code}")
            val body = res.body ?: throw java.io.IOException("empty")
            file.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n); out.write(buf, 0, n) }
                }
            }
        }
        val got = digest.digest().joinToString("") { "%02x".format(it) }
        if (want != null && want != got) { file.delete(); throw BrokenDownload() }
        return file
    }

    private fun commit(c: Context, file: File) {
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(c.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("base.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(c, id, Intent(c, UpdateReceiver::class.java).setPackage(c.packageName), flags)
            s.commit(pending.intentSender)
        }
    }

    internal fun finished(status: Int) {
        _state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> State.Idle
            PackageInstaller.STATUS_FAILURE_ABORTED -> State.Idle // the person said no
            else -> State.Failed("The update was not installed.")
        }
    }
}

/** The system installer's answers: ask the person to confirm, then the outcome. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
            }
            else -> Updater.finished(status)
        }
    }
}
