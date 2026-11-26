package sh.belfry.app

import sh.belfry.core.engine.Demo
import sh.belfry.core.engine.DemoEnc3View
import sh.belfry.core.engine.KeyChange
import sh.belfry.core.engine.MyDevices
import sh.belfry.core.engine.Session
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.EventTemplate
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.outbox.OutboxItem
import sh.belfry.core.outbox.Target

/**
 * Format 3, private groups, for the screens' tests (SPEC 19.88): the demo stays format 1; this installs a made-up
 * [DemoEnc3View] where the demo's Private group "Surprise party" ([Demo.POD_PRIVATE]: Maya, me, Lena, Kofi) is
 * encrypted, my three devices named as in [Enc3Fixture], and adds the events a board needs as if they had been
 * opened from 8572s (rumors: no signature).
 */
object Enc3GroupsFixture {
    const val G = Demo.POD_PRIVATE
    private val AT = DemoShots.NOW - 3 * 3600

    /** Format 3 on SESSION for the group: CANT (its entries don't add up), its key CHANGES, messages WAITING (opening, no entry). */
    fun install(session: Session, cant: Boolean = false, changes: List<KeyChange> = emptyList(), waiting: Pair<Int, Int>? = null) {
        session.demoEnc3View = DemoEnc3View(
            counts = mapOf(Demo.maya.pubkey to 3, Demo.lena.pubkey to 2, Demo.kofi.pubkey to 0, Demo.aroha.pubkey to 1),
            myDevices = MyDevices(listOf(Enc3Fixture.HERE, Enc3Fixture.MAC), emptyList(), mapOf(Enc3Fixture.HERE to Enc3Fixture.AUG12, Enc3Fixture.MAC to Enc3Fixture.OCT2), Enc3Fixture.HERE),
            mySessions = Enc3Fixture.sessions,
            privateGroups = setOf(G),
            cantCheck = if (cant) setOf(G) else emptySet(),
            changes = if (changes.isEmpty()) emptyMap() else mapOf(G to changes),
            waiting = waiting?.let { mapOf(G to it) } ?: emptyMap(),
        )
    }

    private fun rumor(by: LocalSigner, at: Long, kind: Int, tags: List<List<String>>, content: String): Event =
        by.signNow(EventTemplate(kind, content, listOf(listOf("h", G)) + tags, at)).copy(sig = "")

    fun say(by: LocalSigner, minutes: Long, text: String): Event = rumor(by, AT + minutes * 60, 9, emptyList(), text)

    /** A few of the group's messages (opened rumors). */
    fun talk(session: Session) = session.demoAddEvents(listOf(
        say(Demo.maya, 0, "The cake is ordered: chocolate, with his name in blue."),
        say(Demo.lena, 7, "Great. I can pick it up on Saturday morning."),
    ))

    /** The relay's 9000 / 9001 (in clear: §16.9) about WHO, by BY. */
    fun member(session: Session, add: Boolean, who: String, by: LocalSigner = Demo.maya, minutes: Long = 20) =
        session.demoAddEvents(listOf(by.signNow(EventTemplate(if (add) 9000 else 9001, "", listOf(listOf("h", G), listOf("p", who)), AT + minutes * 60))))

    /** encrypted/group-removed-self: Maya removed me; the relay's member list no longer has me. */
    fun removedMe(session: Session) {
        member(session, add = false, who = session.me, minutes = 30)
        session.demoAddEvents(listOf(Demo.relay.signNow(EventTemplate(39002, "", listOf(listOf("d", G)) + listOf(Demo.maya, Demo.lena, Demo.kofi).map { listOf("p", it.pubkey) }, AT + 30 * 60))))
    }

    /** The inbox card the groups key put into the group as an 8572 (encrypted/group-email). */
    fun mail(session: Session) = session.demoAddEvents(listOf(
        say(Demo.maya, 2, "Did the hall answer about the Saturday booking?"),
        rumor(Demo.groups, AT + 15 * 60, 9, listOf(
            listOf("client", "belfry-mail"), listOf("mail-from", "bookings@townhall.example"), listOf("mail-name", "Town Hall"),
            listOf("subject", "Hall booking, Saturday 14 Nov"), listOf("belfry-auth", "pass"),
        ), "The hall is yours from 6 PM. The key is at the front desk."),
        say(Demo.lena, 20, "Perfect!"),
    ))

    /** A message of mine format 3 holds (ERROR: `held: enc3:<REASON>` or `invalid: enc3:TOO_MANY`), kept on the device. */
    fun held(session: Session, text: String, error: String) {
        session.store.putOutbox(OutboxItem("local-held", Target.GROUPS, 9, text, listOf(listOf("h", G), listOf("demo", "hold")), AT + 30 * 60, error = error, tries = 1))
        session.demoAddEvents(emptyList())
    }
}
