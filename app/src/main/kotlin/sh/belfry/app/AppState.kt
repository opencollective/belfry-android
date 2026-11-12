package sh.belfry.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.belfry.core.engine.AccountList
import sh.belfry.core.engine.AccountRecord
import sh.belfry.core.engine.Invitation
import sh.belfry.core.engine.LinkAccounts
import sh.belfry.core.engine.Onboarding
import sh.belfry.core.engine.OnboardingException
import sh.belfry.core.engine.PendingCode
import sh.belfry.core.engine.Session
import sh.belfry.core.engine.Vault
import sh.belfry.core.net.Community
import sh.belfry.core.net.EventLink
import sh.belfry.core.net.InviteLink
import sh.belfry.core.push.PushOpen

/** Where the app is: starting, signed out, looking at a guest link, or in. */
sealed interface Phase {
    data object Loading : Phase
    data object SignedOut : Phase
    /** Signed out and opened from a guest link: the join screen. */
    data class Joining(val link: InviteLink) : Phase
    data class SignedIn(val session: Session) : Phase
}

/** Signed in and adding another account: the sign-in screens over the app (a back arrow returns). */
sealed interface Adding {
    data object SignIn : Adding
    /** A guest link for a belfry where this device has no account. */
    data class Join(val link: InviteLink) : Adding
}

/** What the switcher shows of an account that is not open (the open one is read from its session). */
data class AccountsView(val list: AccountList = AccountList.EMPTY, val unread: Set<String> = emptySet())

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

    /** The native sign-in is waiting for the 6-digit code (screen 2), also after the app was killed meanwhile. */
    private val _pendingCode = MutableStateFlow<PendingCode?>(null)
    val pendingCode: StateFlow<PendingCode?> = _pendingCode

    /** Signed in and opened from a guest link: offer to join as yourself. */
    private val _joinOffer = MutableStateFlow<InviteLink?>(null)
    val joinOffer: StateFlow<InviteLink?> = _joinOffer

    /** Opened from an event's address: the event page opens once signed in (kept until then). */
    private val _eventLink = MutableStateFlow<EventLink?>(null)
    val eventLink: StateFlow<EventLink?> = _eventLink

    /** An event's address: opened in an account of that belfry when there is one (the open one first). */
    fun onEventLink(link: EventLink) {
        val s = _session.value
        val other = if (s == null || s.community.domain == link.domain) null else accountsNow().onDomain(link.domain).firstOrNull()
        if (other == null) { _eventLink.value = link; return }
        scope.launch { if (switchTo(other.id)) _eventLink.value = link }
    }

    /** A notification was tapped: what it opens, once signed in (kept until then). */
    private val _pushOpen = MutableStateFlow<PushOpen?>(null)
    val pushOpen: StateFlow<PushOpen?> = _pushOpen

    /** [account]: whose notification it was (an account id); that account is opened first. */
    fun onPushOpen(open: PushOpen, account: String? = null) {
        val s = _session.value
        when {
            account == null || s?.accountId == account -> _pushOpen.value = open
            s == null -> { wanted = account; _pushOpen.value = open } // boot() opens that account
            else -> scope.launch { if (switchTo(account)) _pushOpen.value = open }
        }
    }
    fun forgetPushOpen() { _pushOpen.value = null }

    // ---- several accounts (SPEC 19.24) ----------------------------------------------------------------

    private val _accounts = MutableStateFlow(AccountsView())
    /** The accounts on this device, for the switcher. */
    val accounts: StateFlow<AccountsView> = _accounts

    private val _adding = MutableStateFlow<Adding?>(null)
    /** Not null while another account is being added over the open one. */
    val adding: StateFlow<Adding?> = _adding

    /** A guest link for a belfry where this device has several accounts: which one joins? */
    private val _chooseFor = MutableStateFlow<InviteLink?>(null)
    val chooseFor: StateFlow<InviteLink?> = _chooseFor

    /** The account a tapped notification belongs to, when it came before the app had started. */
    @Volatile private var wanted: String? = null
    /** The link whose account the person picked (or just signed in for): not asked again. */
    @Volatile private var picked: InviteLink? = null
    private var watcher: Job? = null

    /** Which accounts have something new (a notification came while another was open); the app's push code tells. */
    @Volatile var unreadAccounts: (() -> Set<String>)? = null
    @Volatile var clearUnread: ((String) -> Unit)? = null

    private fun accountsNow(): AccountList = try { onboarding.accounts } catch (e: Exception) { AccountList.EMPTY }

    /** Reads the list again (the vault decrypts with the Keystore: off the main thread). */
    fun refreshAccounts() {
        scope.launch {
            _accounts.value = withContext(Dispatchers.Default) {
                AccountsView(accountsNow(), try { unreadAccounts?.invoke() } catch (e: Exception) { null } ?: emptySet())
            }
        }
    }

    /** "Add an account": the sign-in screen over the app. The open account stays as it is. */
    fun addAccount() {
        _message.value = null
        _adding.value = Adding.SignIn
    }

    /** The back arrow of "Add an account". */
    fun cancelAdd() {
        cancelCode()
        // a guest link that led here is dropped with it
        try { onboarding.pendingInvite = null } catch (e: Exception) { }
        _adding.value = null
        _message.value = null
    }

    /**
     * Opens another account of this device: the open one is stopped (its relays and subscriptions
     * end; what its outbox holds stays queued until it is opened again) and the other starts from
     * what its own store holds. False when that account cannot be opened.
     */
    suspend fun switchTo(id: String): Boolean {
        if (_session.value?.accountId == id) return true
        // in the app's own scope: the screen that asked goes away with the account it showed
        return scope.async {
            if (_session.value?.accountId == id) return@async true
            val s = try { onboarding.resume(id) } catch (e: Exception) { null }
            if (s == null) { refreshAccounts(); false } else { enter(s); true }
        }.await()
    }

    /** The person picked which account uses the guest link. */
    suspend fun chooseAccount(id: String) {
        val link = _chooseFor.value ?: return
        _chooseFor.value = null
        if (_session.value?.accountId == id) { _joinOffer.value = link; return }
        picked = link
        switchTo(id)
    }

    fun forgetChoice() {
        _chooseFor.value = null
        forgetInvite()
    }

    /**
     * A guest link while signed in: the account of that belfry uses it (opened first when it is not
     * the open one); several accounts there: ask which; none: the join flow adds an account.
     */
    private fun routeInvite(link: InviteLink) {
        val s = _session.value ?: return
        val chosen = picked == link
        picked = null
        when (val found = accountsNow().forLink(link.domain)) {
            LinkAccounts.None -> _adding.value = Adding.Join(link)
            is LinkAccounts.One ->
                if (found.account.id == s.accountId) _joinOffer.value = link
                else scope.launch { if (!switchTo(found.account.id)) _adding.value = Adding.Join(link) }
            is LinkAccounts.Several ->
                if (chosen && found.accounts.any { it.id == s.accountId }) _joinOffer.value = link else _chooseFor.value = link
        }
    }

    /** The open account's name, address and picture, kept for the switcher as its session learns them. */
    private fun watch(s: Session) {
        watcher?.cancel()
        watcher = scope.launch(Dispatchers.Default) {
            s.world.map { w ->
                val me = w.profiles[w.me]
                listOf(w.realName(w.me), w.myAddress ?: "", w.ctx.communityName.ifEmpty { s.community.name }, me?.picture ?: "")
            }.distinctUntilChanged().collect { (name, address, community, picture) ->
                try {
                    val list = onboarding.describe(s.accountId, name, address, community, picture, s.guest.value != null)
                    _accounts.value = _accounts.value.copy(list = list)
                } catch (e: Exception) { }
            }
        }
    }

    /** Told when a session starts (the app registers it for push). */
    @Volatile var onSignedIn: ((Session) -> Unit)? = null
    /** Runs before signing out, while the session can still sign (push: remove this phone). */
    @Volatile var beforeSignOut: (suspend (Session) -> Unit)? = null
    fun forgetEventLink() { _eventLink.value = null }

    private var booted = false
    @Volatile private var online = true

    /** Opens the saved session, if any (works offline). Safe to call more than once. */
    fun boot() {
        if (booted) return
        booted = true
        scope.launch {
            val s = try { wanted?.let { onboarding.resume(it) } ?: onboarding.resume() } catch (e: Exception) { null }
            wanted = null
            if (s != null) enter(s) else {
                // the vault decrypts with the Keystore: not on the main thread
                val (invite, code) = withContext(Dispatchers.Default) {
                    (try { onboarding.pendingInvite } catch (e: Exception) { null }) to (try { onboarding.pendingCode } catch (e: Exception) { null })
                }
                _pendingCode.value = code
                _phase.value = if (invite != null) Phase.Joining(invite) else Phase.SignedOut
            }
        }
    }

    /** [fresh]: this session was just made by a sign-in or a join (not reopened, not switched to). */
    private fun enter(s: Session, fresh: Boolean = false) {
        // one account is live at a time: the one that was open stops
        val old = _session.value
        if (old != null && old !== s) try { old.stop() } catch (e: Exception) { }
        s.start()
        s.setOnline(online)
        _session.value = s
        _joinOffer.value = null
        _adding.value = null
        _phase.value = Phase.SignedIn(s)
        // its dot in the switcher goes (the vault decrypts with the Keystore: not on the main thread)
        scope.launch {
            withContext(Dispatchers.Default) { try { clearUnread?.invoke(s.accountId) } catch (e: Exception) { } }
            refreshAccounts()
        }
        watch(s)
        try { onSignedIn?.invoke(s) } catch (e: Exception) { }
        // (the vault decrypts with the Keystore: not on the main thread)
        scope.launch {
            val invite = withContext(Dispatchers.Default) { try { onboarding.pendingInvite } catch (e: Exception) { null } }
            if (invite != null && _session.value === s) {
                // signed in to use this link: this is the account, even when another one is in that belfry
                if (fresh && s.community.domain == invite.domain) picked = invite
                routeInvite(invite)
            }
            // an event's address that waited for the app to start: its belfry's account, when it is not this one
            val ev = _eventLink.value
            if (ev != null && _session.value === s && ev.domain != s.community.domain) {
                withContext(Dispatchers.Default) { accountsNow().onDomain(ev.domain).firstOrNull() }?.let { switchTo(it.id) }
            }
        }
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
                enter(onboarding.finishSignIn(code, state, error), fresh = true)
            } catch (e: OnboardingException) {
                _message.value = e.message
            } catch (e: Exception) {
                _message.value = "Not signed in. Try again."
            } finally {
                _finishing.value = false
            }
        }
    }

    /**
     * Native sign-in, screen 1: ADDRESS is `name@domain`; a code goes to the member's e-mail and
     * [pendingCode] opens screen 2. Throws [OnboardingException] with a fixed message (code
     * `browser`: an older belfry, offer the browser).
     */
    suspend fun requestCode(address: String) {
        _message.value = null
        _pendingCode.value = onboarding.requestCode(address)
    }

    /** "Send a new code". */
    suspend fun resendCode() {
        _pendingCode.value = onboarding.resendCode()
    }

    /** Back to screen 1. */
    fun cancelCode() {
        try { onboarding.cancelCode() } catch (e: Exception) { }
        _pendingCode.value = null
    }

    /**
     * Screen 2: the six digits. In on success. A failure that ends the request (expired, another
     * key needed…) goes back to screen 1 with its fixed message; the others throw for screen 2.
     */
    suspend fun verifyCode(code: String) {
        try {
            enter(onboarding.verifyCode(code), fresh = true)
            _pendingCode.value = null
        } catch (e: OnboardingException) {
            val still = withContext(Dispatchers.Default) { try { onboarding.pendingCode } catch (x: Exception) { null } }
            if (still == null) {
                _message.value = e.message
                _pendingCode.value = null
            }
            throw e
        }
    }

    /** The app was opened with a guest link. */
    fun onInviteLink(link: InviteLink) {
        try { onboarding.pendingInvite = link } catch (e: Exception) { }
        when (_phase.value) {
            is Phase.SignedIn -> { _joinOffer.value = null; _chooseFor.value = null; routeInvite(link) }
            is Phase.Loading -> Unit // boot() picks the pending invite up
            else -> _phase.value = Phase.Joining(link)
        }
    }

    suspend fun inviteInfo(link: InviteLink): Invitation = onboarding.inviteInfo(link)

    /** Joins as a new guest with a name only. Throws [OnboardingException] with a fixed message. */
    suspend fun joinAsNew(invitation: Invitation, name: String) {
        val s = onboarding.joinAsNew(invitation, name)
        forgetInvite()
        enter(s, fresh = true)
    }

    /** "Already have an account? Sign in": the link stays pending and is offered again after sign-in. */
    fun signInInstead() {
        if (_phase.value is Phase.SignedIn) _adding.value = Adding.SignIn else _phase.value = Phase.SignedOut
    }

    /** Drops the guest link (closed, or the person said no, or it was used). */
    fun forgetInvite() {
        try { onboarding.pendingInvite = null } catch (e: Exception) { }
        _joinOffer.value = null
        _chooseFor.value = null
        if (_adding.value is Adding.Join) _adding.value = null
        if (_phase.value is Phase.Joining) _phase.value = Phase.SignedOut
    }

    suspend fun demo() {
        _message.value = null
        cancelCode()
        enter(onboarding.demo(), fresh = true)
    }

    /**
     * Signs out of the open account only and wipes this device's copy of it; another account of
     * this device, if there is one, opens next. Returns the address that also ends the browser's
     * session with the belfry (null for the demo); the caller may open it. The work runs in the
     * app's own scope: the screen that asked goes away meanwhile.
     */
    suspend fun signOut(): String? {
        val s = _session.value ?: return null
        watcher?.cancel()
        _phase.value = Phase.Loading
        return scope.async {
            val url = withContext(Dispatchers.Default) {
                // the belfry forgets this phone's push device first (signed by the session), then the session is revoked
                try { beforeSignOut?.invoke(s) } catch (e: Exception) { }
                try { onboarding.signOut(s) } catch (e: Exception) { null }
            }
            if (_session.value === s) _session.value = null
            val next = try { onboarding.resume() } catch (e: Exception) { null }
            if (next != null) enter(next) else {
                refreshAccounts()
                _phase.value = Phase.SignedOut
            }
            url
        }.await()
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
