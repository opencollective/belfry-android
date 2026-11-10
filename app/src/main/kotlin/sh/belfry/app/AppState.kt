package sh.belfry.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.belfry.core.engine.Invitation
import sh.belfry.core.engine.Onboarding
import sh.belfry.core.engine.OnboardingException
import sh.belfry.core.engine.Session
import sh.belfry.core.engine.Vault
import sh.belfry.core.net.Community
import sh.belfry.core.net.InviteLink

/** Where the app is: starting, signed out, looking at a guest link, or in. */
sealed interface Phase {
    data object Loading : Phase
    data object SignedOut : Phase
    /** Signed out and opened from a guest link: the join screen. */
    data class Joining(val link: InviteLink) : Phase
    data class SignedIn(val session: Session) : Phase
}

/**
 * The app's state above any screen: the session (or none), sign-in and guest-link flows.
 * Not tied to an Activity. Every message it exposes is a fixed text chosen by an error key,
 * never text from a URL or from the server.
 */
class AppState(private val onboarding: Onboarding, private val scope: CoroutineScope) {
    private val _phase = MutableStateFlow<Phase>(Phase.Loading)
    val phase: StateFlow<Phase> = _phase

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session

    /** A fixed message for the sign-in screen (a sign-in that did not work), or null. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** A sign-in is being finished (the browser came back). */
    private val _finishing = MutableStateFlow(false)
    val finishing: StateFlow<Boolean> = _finishing

    /** Signed in and opened from a guest link: offer to join as yourself. */
    private val _joinOffer = MutableStateFlow<InviteLink?>(null)
    val joinOffer: StateFlow<InviteLink?> = _joinOffer

    private var booted = false
    @Volatile private var online = true

    /** Opens the saved session, if any (works offline). Safe to call more than once. */
    fun boot() {
        if (booted) return
        booted = true
        scope.launch {
            val s = try { onboarding.resume() } catch (e: Exception) { null }
            if (s != null) enter(s) else {
                val invite = try { onboarding.pendingInvite } catch (e: Exception) { null }
                _phase.value = if (invite != null) Phase.Joining(invite) else Phase.SignedOut
            }
        }
    }

    private fun enter(s: Session) {
        s.start()
        s.setOnline(online)
        _session.value = s
        _phase.value = Phase.SignedIn(s)
        val invite = try { onboarding.pendingInvite } catch (e: Exception) { null }
        if (invite != null) _joinOffer.value = invite
    }

    fun clearMessage() { _message.value = null }

    /** The community at this address. Throws [OnboardingException] with a fixed message. */
    suspend fun discover(domain: String): Community = onboarding.discover(domain)

    /** Starts a sign-in; returns the address to open in a Custom Tab. */
    fun beginSignIn(community: Community): String {
        _message.value = null
        return onboarding.beginSignIn(community)
    }

    /** The browser came back to sh.belfry.app://connect with these values (and nothing else is read). */
    fun onConnectCallback(code: String?, state: String?, error: String?) {
        if (_finishing.value) return
        _finishing.value = true
        scope.launch {
            try {
                enter(onboarding.finishSignIn(code, state, error))
            } catch (e: OnboardingException) {
                _message.value = e.message
            } catch (e: Exception) {
                _message.value = "Not signed in. Try again."
            } finally {
                _finishing.value = false
            }
        }
    }

    /** The app was opened with a guest link. */
    fun onInviteLink(link: InviteLink) {
        try { onboarding.pendingInvite = link } catch (e: Exception) { }
        when (_phase.value) {
            is Phase.SignedIn -> _joinOffer.value = link
            is Phase.Loading -> Unit // boot() picks the pending invite up
            else -> _phase.value = Phase.Joining(link)
        }
    }

    suspend fun inviteInfo(link: InviteLink): Invitation = onboarding.inviteInfo(link)

    /** Joins as a new guest with a name only. Throws [OnboardingException] with a fixed message. */
    suspend fun joinAsNew(invitation: Invitation, name: String) {
        val s = onboarding.joinAsNew(invitation, name)
        forgetInvite()
        enter(s)
    }

    /** "Already have an account? Sign in": the link stays pending and is offered again after sign-in. */
    fun signInInstead() { _phase.value = Phase.SignedOut }

    /** Drops the guest link (closed, or the person said no, or it was used). */
    fun forgetInvite() {
        try { onboarding.pendingInvite = null } catch (e: Exception) { }
        _joinOffer.value = null
        if (_phase.value is Phase.Joining) _phase.value = Phase.SignedOut
    }

    suspend fun demo() {
        _message.value = null
        enter(onboarding.demo())
    }

    /**
     * Signs out and wipes the device's copy. Returns the address that also ends the browser's
     * session with the belfry (null for the demo); the caller may open it.
     */
    suspend fun signOut(): String? {
        val s = _session.value ?: return null
        _session.value = null
        _phase.value = Phase.SignedOut
        return withContext(Dispatchers.Default) { try { onboarding.signOut(s) } catch (e: Exception) { null } }
    }

    /** The app came to the foreground. */
    fun wake() { _session.value?.wake() }

    fun setOnline(value: Boolean) {
        online = value
        _session.value?.setOnline(value)
    }
}

/** The Keystore-backed secrets as the core's [Vault]; a broken Keystore reads as "nothing saved". */
class SecretsVault(private val secrets: sh.belfry.app.data.Secrets) : Vault {
    override fun get(name: String): String? = try { secrets.get(name) } catch (e: Exception) { null }
    override fun set(name: String, value: String) { try { secrets.set(name, value) } catch (e: Exception) { } }
    override fun remove(name: String) { try { secrets.remove(name) } catch (e: Exception) { } }
}
