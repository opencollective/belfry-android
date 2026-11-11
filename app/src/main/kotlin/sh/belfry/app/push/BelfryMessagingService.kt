package sh.belfry.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import sh.belfry.app.BelfryApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** FCM data-only messages from the push gateway: `data.b` = the encrypted notification. */
class BelfryMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val payload = message.data["b"] ?: return
        val sent = (application as? BelfryApp)?.currentSession()?.lastDmSentMs ?: 0L
        try { AndroidPush.show(applicationContext, payload, sent) } catch (e: Exception) { /* never crash on a push */ }
    }

    /** A new FCM token: register it with the belfry now if signed in (else at the next start). */
    override fun onNewToken(token: String) {
        val app = application as? BelfryApp ?: return
        val session = app.currentSession() ?: return
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try { AndroidPush.register(applicationContext, session) } catch (e: Exception) { }
        }
    }
}
