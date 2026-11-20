package sh.belfry.core.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import sh.belfry.core.net.RemoteApp
import sh.belfry.core.net.RemoteAppPreview
import sh.belfry.core.net.RemoteAppsApi

/**
 * Sign in to another app with this account (SPEC 19.47): the belfry's server is the app's NIP-46
 * remote signer; this only asks it (preview, connect, list, disconnect). Refused in guest mode,
 * as every write. The demo answers on the device. The app's code is passed through, never kept.
 */
class RemoteAppsService internal constructor(private val s: Session) {
    private val demo: Boolean get() = s.localOnly

    private val _apps = MutableStateFlow<List<RemoteApp>?>(null)
    /** The connected apps, null until the belfry answered once in this session. */
    val apps: StateFlow<List<RemoteApp>?> = _apps

    private suspend fun <T> api(write: Boolean, block: suspend (RemoteAppsApi) -> T): T = s.apiCall(write) { block(RemoteAppsApi(it, s.signer)) }

    suspend fun preview(code: String): RemoteAppPreview {
        s.checkWrite()
        if (demo) return RemoteAppPreview(DEMO_NAME, "https://$DEMO_HOST", DEMO_HOST, null, asksSign = true, asksDecrypt = true, known = _apps.value.orEmpty().any { it.host == DEMO_HOST })
        return api(write = true) { it.preview(code) }
    }

    suspend fun connect(code: String, decrypt: Boolean): RemoteApp? {
        if (demo) {
            val now = s.now()
            val app = RemoteApp("demo-${now}", DEMO_NAME, "https://$DEMO_HOST", DEMO_HOST, null, decrypt, now, null, now + 30 * 86400)
            _apps.value = listOf(app) + _apps.value.orEmpty().filter { it.host != DEMO_HOST }
            return app
        }
        val app = api(write = true) { it.connect(code, decrypt) }
        try { refresh() } catch (e: Exception) { }
        return app
    }

    suspend fun refresh(): List<RemoteApp> {
        if (demo) return _apps.value.orEmpty().also { _apps.value = it }
        val list = api(write = false) { it.list() }
        _apps.value = list
        return list
    }

    suspend fun disconnect(id: String) {
        if (demo) { _apps.value = _apps.value.orEmpty().filter { it.id != id }; return }
        api(write = true) { it.disconnect(id) }
        _apps.value = _apps.value.orEmpty().filter { it.id != id }
        try { refresh() } catch (e: Exception) { }
    }

    companion object {
        const val DEMO_NAME = "Git Workshop"
        const val DEMO_HOST = "gitworkshop.dev"
    }
}
