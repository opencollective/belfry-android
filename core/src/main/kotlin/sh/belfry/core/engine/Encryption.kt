package sh.belfry.core.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import sh.belfry.core.mail.MailThreads
import sh.belfry.core.model.AccessAsk
import sh.belfry.core.model.ConvSeal
import sh.belfry.core.model.DeviceChange
import sh.belfry.core.model.DeviceKind
import sh.belfry.core.model.DeviceNotice
import sh.belfry.core.model.LockedDevice
import sh.belfry.core.model.MyDevice
import sh.belfry.core.model.PersonDevices
import sh.belfry.core.model.SealState
import sh.belfry.core.model.SealedText
import sh.belfry.core.model.SharedBlock

/**
 * End-to-end encrypted conversations (canvas "Messages · encryption", 2026-11-20): SCREENS ONLY.
 * No key, no crypto and no wire until the lead's contract (collective-belfry docs/sealed.md and
 * its vectors) arrives. Outside the demo nothing is sealed, so no lock shows anywhere and every
 * action refuses. The demo seals its own conversations and answers on the device.
 */
class EncryptionService internal constructor(private val s: Session) {
    private val demo: Boolean get() = s.localOnly

    private val _state = MutableStateFlow(if (demo) demoState() else SealState())
    val state: StateFlow<SealState> = _state

    private fun notYet(): Nothing = throw ActionException("sealed", "Not available on this belfry yet.")

    private fun engine(): sh.belfry.core.sealed.SealedEngine = s.sealed ?: notYet()

    /** A failure as a fixed sentence (Allow / Not me / Remove / Share must show failures, 294c94f (5)). */
    private suspend fun <T> shown(fallback: String, block: suspend () -> T): T = try { block() } catch (e: ActionException) { throw e } catch (e: sh.belfry.core.sealed.SealedEngine.TooMany) {
        throw ActionException("too_many_devices", sh.belfry.core.model.SealedText.TOO_MANY)
    } catch (e: sh.belfry.core.sealed.SealedEngine.NotDone) {
        throw ActionException("not_done", e.message ?: fallback)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
        throw ActionException("failed", fallback)
    }

    /**
     * The real state from the engine (outside the demo): my devices with their names, each sealed
     * conversation's people as counts and its device lines (from when this device first saw it
     * sealed), "locked" only when this device is not in my head, and the requests to answer.
     */
    suspend fun refresh() {
        if (demo) return
        val e = s.sealed ?: return
        val me = s.me
        val mine = e.chainOf(me)
        val names = try { e.deviceNames() } catch (x: Exception) { emptyMap() }
        fun kindOf(k: String?) = when (k) { "tablet" -> DeviceKind.TABLET; "computer" -> DeviceKind.COMPUTER; else -> DeviceKind.PHONE }
        val myDevices = mine?.devices.orEmpty().map { d ->
            val n = names[d]
            MyDevice(
                d, n?.name ?: if (d == e.device.pubkey) s.deviceName else "A device", kindOf(n?.kind ?: if (d == e.device.pubkey) s.deviceKind else null),
                mine!!.addedAt(d) ?: 0, mine.addedFrom(d)?.let { names[it]?.name }, thisDevice = d == e.device.pubkey,
            )
        }
        val dms = s.dms.value
        val world = s.world.value
        val sealed = e.sealedSince.toMap().mapValues { (conv, since) ->
            if (MailThreads.isMail(conv)) ConvSeal(mail = listOf(dms.convName(conv, world)), since = since)
            else {
                val peers = MailThreads.peers(conv).filter { it != me }
                ConvSeal(
                    others = peers.map { p ->
                        val c = e.chainOf(p)
                        val last = c?.changesAfter(null)?.lastOrNull()
                        PersonDevices(p, c?.devices?.size ?: 0, last?.let { DeviceChange(it.added, it.at) })
                    },
                    notices = (peers + me).flatMap { p ->
                        e.chainOf(p)?.changesAfter(null).orEmpty().filter { it.at >= since }.map { ch ->
                            DeviceNotice("$p:${ch.device}:${ch.at}", ch.at, p, if (ch.added) DeviceNotice.Kind.ADDED else DeviceNotice.Kind.REMOVED,
                                device = if (p == me) names[ch.device]?.name else null)
                        }
                    },
                    since = since,
                )
            }
        }
        val asks = ArrayList<AccessAsk>()
        var linked: String? = _state.value.linked
        if (e.mine == sh.belfry.core.sealed.SealedEngine.Mine.IN_HEAD) {
            for (req in try { e.requests() } catch (x: Exception) { emptyList() }) {
                when (val r = try { e.onRequest(req) } catch (x: Exception) { sh.belfry.core.sealed.SealedEngine.OnRequest.Ignore }) {
                    is sh.belfry.core.sealed.SealedEngine.OnRequest.Ask -> if (asks.none { it.id == r.device }) asks.add(AccessAsk(r.device, r.name, DeviceKind.PHONE, req.createdAt))
                    sh.belfry.core.sealed.SealedEngine.OnRequest.Allowed -> linked = linked ?: "Your new device"
                    else -> {}
                }
            }
        }
        val waiting = e.mine == sh.belfry.core.sealed.SealedEngine.Mine.WAITING
        _state.value = SealState(
            myDevices = myDevices, sealed = sealed, earlierLocked = waiting,
            // only "this device is not in my head" hides messages or the composer (294c94f (1))
            locked = if (waiting) sealed.keys else emptySet(),
            asks = asks, linked = linked,
        )
    }

    /** Give my new device access (the prompt on my other devices). The first to answer wins. */
    suspend fun allow(ask: String) {
        s.checkWrite()
        if (!demo) {
            shown("Not allowed: try again.") { engine().allow(ask) }
            _state.value = _state.value.let { st -> st.copy(asks = st.asks.filter { it.id != ask }) }
            refresh()
            return
        }
        delay(400)
        // sealed.md §5.2: at most 20 devices; refused before any key goes out
        if (_state.value.myDevices.size >= 20) throw ActionException("too_many_devices", SealedText.TOO_MANY)
        _state.value = _state.value.let { st -> st.copy(asks = st.asks.filter { it.id != ask }) }
    }

    /** Not me: that device is removed and signed out. */
    suspend fun notMe(ask: String) {
        s.checkWrite()
        if (!demo) {
            shown("That device could not be signed out. Try again.") { engine().notMe(ask) }
            _state.value = _state.value.let { st -> st.copy(asks = st.asks.filter { it.id != ask }) }
            return
        }
        delay(400)
        _state.value = _state.value.let { st -> st.copy(asks = st.asks.filter { it.id != ask }) }
    }

    /**
     * Share a conversation's history with someone's new device. Its amber line becomes the plain
     * "<name> added a device" (canvas v81), still opening the Encryption screen.
     */
    suspend fun shareHistory(conv: String) {
        s.checkWrite()
        if (!demo) notYet()
        delay(400)
        val st = _state.value
        val seal = st.sealed[conv] ?: return
        val notices = seal.notices.map { if (it.kind == DeviceNotice.Kind.NEW_LOCKED) it.copy(kind = DeviceNotice.Kind.ADDED, device = null) else it }
        _state.value = st.copy(sealed = st.sealed + (conv to seal.copy(notices = notices, waiting = null)))
    }

    /** Remove one of my devices (never this one: that is Sign out). */
    suspend fun removeDevice(id: String) {
        s.checkWrite()
        if (!demo) {
            if (id == s.sealed?.device?.pubkey) throw ActionException("this-device", "This device can't be removed here: use Sign out.")
            shown("Not removed: try again.") { engine().remove(id) }
            refresh()
            return
        }
        val d = _state.value.myDevices.firstOrNull { it.id == id } ?: return
        if (d.thisDevice) throw ActionException("this-device", "This device can't be removed here: use Sign out.")
        delay(400)
        _state.value = _state.value.let { st -> st.copy(myDevices = st.myDevices.filter { it.id != id }) }
    }

    /** A one-time secret for the Link-a-phone QR (only in the QR, never stored; §5.2). Null: no sealed side. */
    fun linkSecret(): String? = if (demo) null else s.sealed?.newLinkSecret()

    /** The linked screen was closed. */
    fun linkedSeen() {
        _state.value = _state.value.copy(linked = null)
    }

    // ---- the demo ------------------------------------------------------------------------------

    /** Demo and tests: this device is new, its earlier conversations locked (the amber banner). */
    fun demoAsNewDevice() {
        if (!demo) return
        val st = _state.value
        val now = s.now()
        // canvas v84: every sealed conversation is locked on a device not yet allowed
        val older = st.sealed.keys.toSet()
        val mine = listOf(
            MyDevice("d-new", "Pixel 8", DeviceKind.PHONE, now - 120, from = null, thisDevice = true, active = now),
        ) + st.myDevices.map { it.copy(thisDevice = false) }
        _state.value = st.copy(earlierLocked = true, locked = older, myDevices = mine)
    }

    /** Demo and tests: a new device of mine asks for access (the prompt over any screen). */
    fun demoAsk(device: String = "Firefox on Linux", kind: DeviceKind = DeviceKind.COMPUTER) {
        if (!demo) return
        val now = s.now()
        _state.value = _state.value.let { st -> st.copy(asks = st.asks + AccessAsk("ask-${st.asks.size + 1}", device, kind, now - 120)) }
    }

    /** Demo and tests: a device just linked by scanning my code. */
    fun demoLinked(device: String = "Galaxy Tab") {
        if (!demo) return
        _state.value = _state.value.copy(linked = device)
    }

    private val DM_MAYA: String get() = "n:" + Demo.maya.pubkey

    private fun demoState(): SealState {
        val now = s.now()
        val me = s.me
        val h = 3600L
        val day = 86400L
        val pixel = MyDevice("d-phone", "Pixel 8", DeviceKind.PHONE, now - 90 * day, thisDevice = true, active = now)
        val laptop = MyDevice("d-laptop", "Firefox on Linux", DeviceKind.COMPUTER, now - 3 * h, from = "Pixel 8", active = now - 3 * day)
        val trio = MailThreads.nostrConv(Demo.tomas.pubkey, listOf(Demo.lena.pubkey, me), me)
        val maya = ConvSeal(
            others = listOf(PersonDevices(Demo.maya.pubkey, 2, DeviceChange(added = false, at = now - 2 * h))),
            notices = listOf(
                DeviceNotice("n1", now - 25 * h + 600, Demo.maya.pubkey, DeviceNotice.Kind.ADDED),
                DeviceNotice("n2", now - 2 * h, Demo.maya.pubkey, DeviceNotice.Kind.REMOVED),
                DeviceNotice("n3", now - 3 * h + 60, me, DeviceNotice.Kind.ADDED, device = laptop.name),
                DeviceNotice("n4", now - 40 * 60, Demo.maya.pubkey, DeviceNotice.Kind.NEW_LOCKED),
            ),
            waiting = LockedDevice(Demo.maya.pubkey, DeviceKind.PHONE, now - 40 * 60),
            since = now - 25 * h + 600,
            // the gate code came the old way after the conversation was sealed (canvas v89)
            notSealed = setOf(now - 35 * 60),
        )
        val group = ConvSeal(
            others = listOf(
                PersonDevices(Demo.lena.pubkey, 3, DeviceChange(added = true, at = now - day)),
                PersonDevices(Demo.tomas.pubkey, 1),
            ),
            since = now - 4 * day,
            // Lena shared the group's history with this device (canvas v86)
            shared = listOf(SharedBlock(Demo.lena.pubkey, now - 3 * day, now - 3 * day + 900)),
        )
        return SealState(
            myDevices = listOf(pixel, laptop),
            sealed = mapOf(
                DM_MAYA to maya,
                trio to group,
                "n:$me" to ConvSeal(),
                MailThreads.mailThreadOf("ana@example.org", "Visiting next week") to ConvSeal(mail = listOf("Ana Pereira")),
                MailThreads.mailThreadOf("permits@cityhall.example", "Street party permit") to ConvSeal(mail = listOf("City Office")),
            ),
        )
    }
}
