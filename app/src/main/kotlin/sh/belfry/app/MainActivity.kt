package sh.belfry.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import sh.belfry.app.ui.AppRoot
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.core.net.EventLink
import sh.belfry.core.net.InviteLink
import sh.belfry.core.net.SignIn

class MainActivity : ComponentActivity() {
    private val state: AppState get() = (application as BelfryApp).state

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        state.boot()
        setContent {
            BelfryTheme { AppRoot(state) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    /**
     * Two kinds of links open the app. Only fixed, named values are read from them and none of
     * their text is ever shown: the sign-in answer (`code`, `state`, `error`) and a guest link
     * (its domain and token, checked by [InviteLink.parse]), an event's address (its domain and
     * link, checked by [EventLink.parse]).
     */
    private fun handle(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (uri.scheme == SignIn.REDIRECT_SCHEME && uri.host == SignIn.REDIRECT_HOST) {
            val q = { name: String -> try { uri.getQueryParameter(name) } catch (e: Exception) { null } }
            state.onConnectCallback(q("code"), q("state"), q("error"))
        } else if (uri.scheme == "https") {
            val url = uri.toString()
            InviteLink.parse(url)?.let { state.onInviteLink(it) } ?: EventLink.parse(url)?.let { state.onEventLink(it) }
        }
    }
}
