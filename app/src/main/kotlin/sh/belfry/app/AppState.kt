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
import sh.belfry.core.model.CardForm
import sh.belfry.core.model.CardInfo
import sh.belfry.core.model.CardKind
import sh.belfry.core.model.CardLink
import sh.belfry.core.model.CardWelcome
import sh.belfry.core.model.HashtagState
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
    /** A guest told "This event is for members" (SPEC 19.71): the e-mail log-in of that belfry, or the application ([apply]), at once. */
    data class WayIn(val community: Community, val apply: Boolean) : Adding
}

/**
 * A guest link being opened (SPEC 19.26): one loading screen from the link to its channel.
 * [channelName] comes from the belfry's answer about the invite (null until it is in).
 */
data class Opening(
    val channelName: String? = null,
    /** The account the link made, once its session exists (only that session ends the loading screen). */
    val account: String? = null,
)

/** A group just started or joined from a printed card, in the belfry at [domain]: its first open welcomes. */
data class CardWelcomed(val domain: String, val group: String, val welcome: CardWelcome)

/** What the sign-in screen starts with after "Sign in instead" (guest mode, an address with an account). */
data class SignInPrefill(val address: String, val hint: String?)

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

    private val _opening = MutableStateFlow<Opening?>(null)
    /** Not null while a guest link is being opened: the loading screen is over everything (AppRoot). */
    val opening: StateFlow<Opening?> = _opening
    /** The channel can be drawn in full (the relay sent what it has, or said no, or 10 s went by). */
    fun doneOpening() { _opening.value = null }

    @Volatile private var prefill: SignInPrefill? = null
    /** Taken once by the sign-in screen. */
    fun takePrefill(): SignInPrefill? = prefill.also { prefill = null }
    /** A prefill waits (guest mode's "Sign in instead"): the sign-in screen shows first. */
    fun hasPrefill(): Boolean = prefill != null

    /** (domain, channel): opened once signed in to that belfry ("Sign in instead"). */
    private val _openAfter = MutableStateFlow<Pair<String, String>?>(null)
    val openAfter: StateFlow<Pair<String, String>?> = _openAfter
    fun forgetOpenAfter() { _openAfter.value = null }

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

    /**
     * Signed out, an event's address (SPEC 19.71): its community when the belfry says the event is
     * for members (the web's unsigned read: 403), else null (an open event, no such event, no
     * answer within 8 s: the app goes on as it did).
     */
    suspend fun membersOnlyEvent(link: EventLink): Community? = try {
        kotlinx.coroutines.withTimeoutOrNull(8_000) {
            val c = onboarding.discover(link.domain)
            if (onboarding.eventDoor(c, link.link) == sh.belfry.core.net.EventDoor.MEMBERS_ONLY) c else null
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }

    /** The event that opens once the way in started by [wayInFor] made a session (a guest's: kept in memory only). */
    @Volatile private var eventAfter: EventLink? = null

    /**
     * A guest of this belfry was told an event is for members: the e-mail log-in (or the
     * application) of this belfry over the app, and EVENT (its link) opens once they are in.
     */
    fun wayInFor(event: String?, apply: Boolean) {
        val s = _session.value ?: return
        _message.value = null
        eventAfter = event?.let { EventLink(s.community.domain, it) }
        _adding.value = Adding.WayIn(s.community, apply)
    }

    // ---- printed cards (SPEC 19.72–19.74) -------------------------------------------------------------

    private val _card = MutableStateFlow<CardLink?>(null)
    /**
     * A printed card's address was opened or scanned: its screen shows (signed out: in place of the
     * first screen; signed in: over the app) until it is dealt with.
     */
    val card: StateFlow<CardLink?> = _card

    private val _cardWelcome = MutableStateFlow<CardWelcomed?>(null)
    /** The group just started or joined from a card: its first open says welcome (in memory: once). */
    val cardWelcome: StateFlow<CardWelcomed?> = _cardWelcome
    fun clearCardWelcome() { _cardWelcome.value = null }

    /** A card's address: used in an account of that belfry when there is one (the open one first), else with no account. */
    fun onCardLink(link: CardLink) {
        try { onboarding.pendingCard = link } catch (e: Exception) { }
        // scanned from "Add an account": that screen goes, the card's shows
        if (_adding.value is Adding.SignIn) _adding.value = null
        val s = _session.value
        val other = if (s == null || s.community.domain == link.domain) null else accountsNow().onDomain(link.domain).firstOrNull()
        if (other == null) { _card.value = link; return }
        scope.launch { switchTo(other.id); _card.value = link }
    }

    /** The card is dealt with (or closed): nothing of it is kept but what was typed. */
    fun forgetCard() {
        _card.value = null
        try { onboarding.pendingCard = null } catch (e: Exception) { }
    }

    private val cardCommunities = java.util.concurrent.ConcurrentHashMap<String, Community>()
    suspend fun cardCommunity(domain: String): Community =
        cardCommunities[domain] ?: onboarding.cardCommunity(domain).also { cardCommunities[domain] = it }

    suspend fun cardInfo(link: CardLink): CardInfo = onboarding.cardInfo(cardCommunity(link.domain), link.id)
    suspend fun cardHashtag(link: CardLink, name: String): HashtagState = onboarding.cardHashtag(cardCommunity(link.domain), name)
    suspend fun cardForm(link: CardLink): CardForm? = withContext(Dispatchers.Default) { try { onboarding.cardForm(link) } catch (e: Exception) { null } }
    suspend fun keepCardForm(link: CardLink, f: CardForm) = withContext(Dispatchers.Default) { try { onboarding.keepCardForm(link, f) } catch (e: Exception) { } }
    suspend fun dropCardForm() = withContext(Dispatchers.Default) { try { onboarding.dropCardForm() } catch (e: Exception) { } }

    /** A visitor, step 1: the code goes to EMAIL. Throws [sh.belfry.core.model.CardFailed]. */
    suspend fun cardStart(link: CardLink, email: String) = onboarding.cardClaimStart(cardCommunity(link.domain), link.id, email)

    /** Step 2: the right code makes the group and signs this phone in; the group opens with its welcome. */
    suspend fun cardVerify(link: CardLink, kind: CardKind, f: CardForm, code: String) {
        val (s, group) = onboarding.cardClaimVerify(cardCommunity(link.domain), link.id, kind, f, code)
        cardIn(s, group)
    }

    /** The code was right and the signer did not answer: once more, without a new code. False: nothing waits any more. */
    suspend fun cardRetry(): Boolean {
        val (s, group) = onboarding.resumeCardClaim() ?: return false
        cardIn(s, group)
        return true
    }

    private fun cardIn(s: Session, group: String) {
        _card.value = null
        _cardWelcome.value = CardWelcomed(s.community.domain, group, CardWelcome.STARTED)
        _openAfter.value = s.community.domain to group
        enter(s, fresh = true)
    }

    /** Signed in: this account just started GROUP from a card. */
    fun cardStarted(domain: String, group: String) {
        forgetCard()
        _cardWelcome.value = CardWelcomed(domain, group, CardWelcome.STARTED)
    }

    /** A claimed card is a guest link: its invite takes the usual way in (SPEC 19.74). */
    fun cardJoin(link: CardLink, group: String, invite: String) {
        forgetCard()
        if (!InviteLink.isToken(invite)) return
        _cardWelcome.value = CardWelcomed(link.domain, group, CardWelcome.JOINED)
        onInviteLink(InviteLink(link.domain, invite))
    }

    /** What the "taken" screen shows of the group someone started (the card's guest link says). Null: no answer. */
    suspend fun cardPreview(link: CardLink, invite: String): sh.belfry.core.net.InviteInfo? =
        try { kotlinx.coroutines.withTimeoutOrNull(8_000) { onboarding.inviteInfo(InviteLink(link.domain, invite)).info } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }

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
        eventAfter = null
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
            // a card that was being dealt with when the app stopped: its screen again
            if (_card.value == null) _card.value = withContext(Dispatchers.Default) { try { onboarding.pendingCard } catch (e: Exception) { null } }
            // a card's claim whose code was right when the app stopped (kept an hour): its session opens now
            try { withContext(Dispatchers.Default) { onboarding.resumeCardClaim() }?.let { (claimed, group) -> cardIn(claimed, group) } } catch (e: Exception) { }
        }
    }

    /** [fresh]: this session was just made by a sign-in or a join (not reopened, not switched to). */
    private fun enter(s: Session, fresh: Boolean = false) {
        // one account is live at a time: the one that was open stops
        val old = _session.value
        if (old != null && old !== s) try { old.stop() } catch (e: Exception) { }
        s.onBecameMember = { channel -> reopenAsMember(s, channel) }
        s.start()
        s.setOnline(online)
        _session.value = s
        _joinOffer.value = null
        _adding.value = null
        // signed in from "This event is for members" as a guest: that event opens in the new session
        eventAfter?.let { ev -> eventAfter = null; if (fresh && ev.domain == s.community.domain) _eventLink.value = ev }
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

    /**
     * This guest became a member (a member link's code, a link used with the account, the
     * register): the account opens again, as the web reloads, so the relays see a member's key and
     * send every channel; CHANNEL opens once it is in.
     */
    private fun reopenAsMember(s: Session, channel: String?) {
        scope.launch {
            if (_session.value !== s) return@launch
            if (!channel.isNullOrEmpty()) _openAfter.value = s.community.domain to channel
            val next = try { onboarding.resume(s.accountId) } catch (e: Exception) { null } ?: return@launch
            if (_session.value === s) enter(next)
        }
    }

    /**
     * A member link's invitation, "Log in instead", after the link was opened on this device: the
     * placeholder guest goes (its session revoked), the sign-in starts, and the link waits to be
     * used with the account once signed in (the "Join with your account?" sheet).
     */
    suspend fun logInForInvite() {
        val s = _session.value ?: return
        val link = s.guest.value?.invite?.let { InviteLink(s.community.domain, it) }
        prefill = null
        signOut(revoke = true)
        if (link != null) withContext(Dispatchers.Default) { try { onboarding.pendingInvite = link } catch (e: Exception) { } }
        if (_session.value != null) _adding.value = Adding.SignIn
    }

    fun clearMessage() { _message.value = null }

    /** A short note shown once over everything ("Signed in as …"), then cleared. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice
    fun clearNotice() { _notice.value = null }

    /**
     * Guest mode: the right code for an address that is an account here signed that account in on
     * this phone (canvas onboarding/guest-code v41). Never a second address or code: the account's
     * session opens, the guest goes from this device, the channel opens as the member, and
     * "Signed in as <address>" shows. When the account's signer can't be reached: "Sign in instead".
     */
    suspend fun guestSignedIn(signIn: sh.belfry.core.net.GuestSignIn, address: String) {
        val guest = _session.value ?: return
        val community = guest.community
        val next = try {
            onboarding.openGuestSignIn(community, signIn)
        } catch (e: Exception) {
            signInInstead(address, signIn.channel ?: "", signIn.moved, signIn.nip05)
            return
        }
        // the guest account goes from this device (the belfry closed it when it moved the channel)
        withContext(Dispatchers.Default) {
            try { beforeSignOut?.invoke(guest) } catch (e: Exception) { }
            try { onboarding.signOut(guest, revoke = !signIn.moved) } catch (e: Exception) { }
        }
        signIn.channel?.takeIf { it.isNotEmpty() }?.let { _openAfter.value = community.domain to it }
        enter(next, fresh = true)
        _notice.value = "Signed in as " + sh.belfry.core.model.GuestSignInRule.addressToSay(signIn.nip05, address, community.domain)
    }

    /** The community at this address. Throws [OnboardingException] with a fixed message. */
    suspend fun discover(domain: String): Community = onboarding.discover(domain)

    /** No link (onboarding/no-link): a belfry's charter for someone with no account, and applying to join it. */
    suspend fun publicCharter(c: Community) = onboarding.charterFor(c)

    // ---- the way in (membership.md 2026-11-19) ----
    fun wayIn(c: Community): sh.belfry.core.net.WayInApi = onboarding.wayIn(c)

    /** A request waiting at this belfry (its domain), shown again when the app opens signed out. */
    fun pendingApplication(): String? = try { onboarding.pendingApplication } catch (e: Exception) { null }
    fun setPendingApplication(domain: String?) { try { onboarding.pendingApplication = domain } catch (e: Exception) { } }

    /** Signed in by the way in: the session (bound to the applicant key) is saved and opened; the key is let go after the signer answered. */
    suspend fun finishWayIn(c: Community, signed: sh.belfry.core.model.WayIn.Signed) {
        val s = onboarding.openWayIn(c, signed)
        withContext(Dispatchers.Default) { try { onboarding.forgetApplicant(c.domain) } catch (e: Exception) { } }
        enter(s, fresh = true)
    }
    suspend fun applyToJoin(c: Community, email: String, motivation: String, agreed: List<String>) = onboarding.applyTo(c, email, motivation, agreed)

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
    /** The community of a "Link your phone" link being signed in to (its code screen shows it). */
    private val _loginCommunity = MutableStateFlow<sh.belfry.core.net.Community?>(null)
    val loginCommunity: StateFlow<sh.belfry.core.net.Community?> = _loginCommunity

    /**
     * "Link your phone" (canvas onboarding/scan-login; vectors/login-links.json): the web's QR or
     * link `https://chat.<domain>/login/<username>`. That account already on the phone: switched
     * to, no code. Else the native sign-in starts at once for that address (the code is mailed now)
     * and the code screen names the community; a new account is added beside the others.
     */
    fun onLoginLink(link: sh.belfry.core.net.LoginLink) {
        // the Link-a-phone code's one-time secret: kept in memory for the sign-in it starts, never stored (§5.2)
        onboarding.pendingLinkSecret = link.linkSecret
        scope.launch {
            val here = withContext(Dispatchers.Default) { accountsNow() }.accounts.firstOrNull { !it.demo && !it.guest && it.domain == link.domain && it.address.lowercase() == link.address }
            if (here != null) {
                val open = _session.value?.accountId == here.id
                if (switchTo(here.id)) _notice.value = if (open) "You're logged in as ${link.address}" else "Switched to ${link.address}"
                return@launch
            }
            _message.value = null
            _loginCommunity.value = try { withContext(Dispatchers.Default) { onboarding.discover(link.domain) } } catch (e: Exception) { null }
            if (_phase.value is Phase.SignedIn) _adding.value = Adding.SignIn else if (_phase.value !is Phase.Loading) _phase.value = Phase.SignedOut
            try {
                requestCode(link.address)
            } catch (e: OnboardingException) {
                _message.value = e.message
            } catch (e: Exception) {
                _message.value = "The code could not be sent. Try again."
            }
        }
    }

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

    /**
     * Opens a guest link with no account here (SPEC 19.26): no question; this device uses the link
     * in guest mode and the loading screen stays until the channel can be drawn. Throws
     * [OnboardingException] with a fixed message (the loading screen is gone then).
     */
    suspend fun openInvite(link: InviteLink) {
        if (_opening.value == null) _opening.value = Opening()
        val s = try {
            onboarding.openInvite(link) { info -> if (_opening.value != null) _opening.value = Opening(info.channelName) }
        } catch (e: Exception) {
            _opening.value = null
            throw e
        }
        forgetInvite()
        _opening.value?.let { _opening.value = it.copy(account = s.accountId) }
        enter(s, fresh = true)
    }

    /**
     * Guest mode, Join: the address typed has an account here already ([moved]: the belfry gave
     * it the channel and closed this guest). The guest account goes from this device, the native
     * sign-in starts (with [nip05], the account's name@domain the belfry named, or else the
     * address typed, when either is a `name@domain` of this belfry) and the channel opens once
     * signed in.
     */
    suspend fun signInInstead(email: String, channel: String, moved: Boolean, nip05: String? = null) {
        val s = _session.value ?: return
        val domain = s.community.domain
        val address = sh.belfry.core.model.GuestMode.signInPrefill(email, domain, nip05)
        prefill = SignInPrefill(address ?: "", if (address == null) "Sign in with your address here (name@$domain), or with the browser using the e-mail you typed." else null)
        if (channel.isNotEmpty()) _openAfter.value = domain to channel
        // a member link: once signed in, the account uses it (the "Join with your account?" sheet)
        val memberLink = s.guest.value?.invite?.let { InviteLink(domain, it) }
        signOut(revoke = !moved)
        if (memberLink != null) withContext(Dispatchers.Default) { try { onboarding.pendingInvite = memberLink } catch (e: Exception) { } }
        if (_session.value != null) _adding.value = Adding.SignIn
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
    suspend fun signOut(revoke: Boolean = true): String? {
        val s = _session.value ?: return null
        watcher?.cancel()
        _phase.value = Phase.Loading
        return scope.async {
            val url = withContext(Dispatchers.Default) {
                // the belfry forgets this phone's push device first (signed by the session), then the session is revoked
                try { beforeSignOut?.invoke(s) } catch (e: Exception) { }
                try { onboarding.signOut(s, revoke) } catch (e: Exception) { null }
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

    /** The app went to the background: quiet sockets are no longer asked (SPEC 19.50). */
    fun background() { _session.value?.background() }

    fun setOnline(value: Boolean) {
        online = value
        _session.value?.setOnline(value)
    }
}

/** The Keystore-backed secrets as the core's [Vault]; a broken Keystore reads as "nothing saved". */
class SecretsVault(private val secrets: sh.belfry.app.data.Secrets) : Vault {
    override fun get(name: String): String? = try { secrets.get(name) } catch (e: Exception) { null }
    override fun set(name: String, value: String) { try { secrets.set(name, value) } catch (e: Exception) { } }
    override fun setChecked(name: String, value: String): Boolean = try { secrets.set(name, value); true } catch (e: Exception) { false }
    override fun remove(name: String) { try { secrets.remove(name) } catch (e: Exception) { } }
}
