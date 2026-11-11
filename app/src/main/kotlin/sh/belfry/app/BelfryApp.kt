package sh.belfry.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import sh.belfry.app.data.Secrets
import sh.belfry.app.push.AndroidPush
import sh.belfry.app.data.SqliteStore
import sh.belfry.core.engine.Onboarding
import sh.belfry.core.net.Relay

class BelfryApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Built on first use (the launcher activity), so tests that never open it pay nothing. */
    val state: AppState by lazy {
        val onboarding = Onboarding(SecretsVault(Secrets(this)), SqliteStore(this), Relay.defaultClient(), scope)
        AppState(onboarding, scope).also { wire(it) }
    }

    @Volatile private var built = false

    /** The signed-in session, when the app's state exists already (for the push service). */
    fun currentSession(): sh.belfry.core.engine.Session? = if (built) state.session.value else null

    /** Foreground → wake the relays; connectivity → online / offline (SPEC 4). */
    private fun wire(state: AppState) {
        built = true
        // push: register on every start once allowed; remove this phone before signing out
        state.onSignedIn = { s -> scope.launch(Dispatchers.Default) { try { AndroidPush.register(this@BelfryApp, s) } catch (e: Exception) { } } }
        state.beforeSignOut = { s -> AndroidPush.unregister(this@BelfryApp, s) }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = state.wake()
        })
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            state.setOnline(cm.activeNetwork != null)
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = state.setOnline(true)
                override fun onLost(network: Network) = state.setOnline(false)
            })
        } catch (e: Exception) {
            // No connectivity service (or no permission): stay "online" and let the relays find out.
        }
    }
}
