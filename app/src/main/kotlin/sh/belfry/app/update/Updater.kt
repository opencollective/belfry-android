package sh.belfry.app.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
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
import sh.belfry.core.update.InstallStep
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
 *
 * Update goes through [tap] ([AppUpdate.installStep]): installs when Android lets Belfry install
 * packages; else [asking] puts up a sheet (UpdateSheetHost) that says why before it opens Android's
 * "Install unknown apps" setting, and [resume] (every start in the foreground) goes on with the
 * update once the person is back with it on (the app may have been killed meanwhile: the release
 * waits in the preferences). Still off, or no such setting, or the installer failed: belfry.sh/android
 * in the browser. (Before 2026-11-21 Update opened the setting with no word, which some devices show
 * as the app's info screen, and nothing went on after it: OSV's report.)
 */
object Updater {
    sealed interface State {
        data object Idle : State
        data object Downloading : State
        data object Installing : State
        /** WEB: the installer itself failed, so the button offers belfry.sh instead of trying again. */
        data class Failed(val message: String, val web: Boolean = false) : State
    }

    /** The sheet before the setting: for this release; AGAIN = back from the setting, still off. */
    data class Ask(val release: Release, val again: Boolean)

    private const val PREFS = "app-update"
    private const val K_AUTO = "auto"
    private const val K_NEXT = "next-check"
    private const val K_HIDDEN = "hidden"
    private const val K_PENDING = "pending"
    private const val K_ASKED = "asked-at"
    private const val K_NO_SETTING = "no-setting"
    private const val HOUR = 3_600_000L

    /** Plain HTTP with timeouts (the relays' client waits forever on a read). */
    private val http: OkHttpClient by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build() }

    private val _available = MutableStateFlow<Release?>(null)
    /** A newer version than this one, when there is one. */
    val available: StateFlow<Release?> = _available
    /** Tests: a newer release to show (the bar, the rows) without asking GitHub. */
    internal fun showForTest(r: Release?) { _available.value = r }
    private val _asking = MutableStateFlow<Ask?>(null)
    val asking: StateFlow<Ask?> = _asking
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
        // a newer version: download it now on a connection that isn't metered, so Update installs at once
        _available.value?.let { r -> if (!metered(c)) prefetch(c, r) }
        return _available.value
    }

    /** The APK of a newer version, downloaded and checked ahead of the tap (null: not yet). */
    private var ready: Pair<String, File>? = null

    private fun metered(c: Context): Boolean = try {
        (c.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered
    } catch (e: Exception) { true }

    private suspend fun prefetch(c: Context, r: Release) {
        if (ready?.first == r.version && ready?.second?.exists() == true) return
        try { ready = r.version to withContext(Dispatchers.IO) { download(c, r) } } catch (e: Exception) { ready = null }
    }

    private fun settingIntent(c: Context, withPackage: Boolean = true) =
        (if (withPackage) Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}")) else Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun canInstall(c: Context) = try { c.packageManager.canRequestPackageInstalls() } catch (e: Exception) { false }

    private fun hasSetting(c: Context): Boolean = !prefs(c).getBoolean(K_NO_SETTING, false) && try {
        settingIntent(c).resolveActivity(c.packageManager) != null || settingIntent(c, false).resolveActivity(c.packageManager) != null
    } catch (e: Exception) { false }

    fun step(c: Context): InstallStep = AppUpdate.installStep(canInstall(c), hasSetting(c), prefs(c).getLong(K_ASKED, 0), System.currentTimeMillis())

    /** What Update does: install, the sheet before the setting, or belfry.sh. */
    suspend fun tap(c: Context, release: Release) {
        if ((_state.value as? State.Failed)?.web == true) { _state.value = State.Idle; openWeb(c); return }
        when (step(c)) {
            InstallStep.INSTALL -> install(c, release)
            InstallStep.ASK -> _asking.value = Ask(release, again = false)
            InstallStep.ASK_AGAIN -> _asking.value = Ask(release, again = true)
            InstallStep.WEB -> openWeb(c)
        }
    }

    fun dismissAsk() { _asking.value = null }

    /** The sheet's "Open the setting": remember the release, then Android's setting (else belfry.sh). */
    fun openSetting(c: Context, release: Release) {
        _asking.value = null
        prefs(c).edit().putString(K_PENDING, AppUpdate.pendingOf(release)).putLong(K_ASKED, System.currentTimeMillis()).apply()
        val opened = listOf(true, false).any { withPackage ->
            try { c.startActivity(settingIntent(c, withPackage)); true } catch (e: ActivityNotFoundException) { false } catch (e: SecurityException) { false }
        }
        if (!opened) {
            prefs(c).edit().putBoolean(K_NO_SETTING, true).remove(K_PENDING).apply()
            openWeb(c)
        }
    }

    /** belfry.sh/android in the browser (the download page of the newest release). */
    fun openWeb(c: Context) {
        _asking.value = null
        try {
            c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppUpdate.WEB)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            _state.value = State.Failed("Open belfry.sh/android in a browser to update.")
        }
    }

    /** Back in the foreground after the setting: go on with the update, or ask again. */
    suspend fun resume(c: Context) {
        val p = prefs(c)
        val release = AppUpdate.pendingFrom(p.getString(K_PENDING, null)) ?: return
        p.edit().remove(K_PENDING).apply()
        if (System.currentTimeMillis() - p.getLong(K_ASKED, 0) !in 0..AppUpdate.ASKED_FOR_MS) return
        if (canInstall(c)) {
            p.edit().remove(K_ASKED).apply()
            install(c, release)
        } else {
            _asking.value = Ask(release, again = true)
        }
    }

    /** Download RELEASE and open the system's install screen (Android lets Belfry install: see [tap]). */
    suspend fun install(c: Context, release: Release) {
        if (!canInstall(c)) { _asking.value = Ask(release, again = false); return }
        try {
            // downloaded ahead when the check found it; else now
            val file = ready?.takeIf { it.first == release.version && it.second.exists() }?.second
                ?: run { _state.value = State.Downloading; withContext(Dispatchers.IO) { download(c, release) } }
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
            else -> State.Failed("The update was not installed. Get it from belfry.sh.", web = true)
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
