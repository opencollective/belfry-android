package sh.belfry.core.engine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import sh.belfry.core.crypto.Bech32
import sh.belfry.core.crypto.Nip19
import sh.belfry.core.crypto.sha256Hex
import sh.belfry.core.dm.DirectMessages
import sh.belfry.core.dm.GiftWrap
import sh.belfry.core.mail.MailThreads
import sh.belfry.core.mail.Mime
import sh.belfry.core.model.Actions
import sh.belfry.core.model.pinMessage
import sh.belfry.core.model.Draft
import sh.belfry.core.model.NewEvent
import sh.belfry.core.model.NewProposal
import sh.belfry.core.model.People
import sh.belfry.core.model.PodAccess
import sh.belfry.core.model.ProposalStatus
import sh.belfry.core.model.Rsvp
import sh.belfry.core.model.Share
import sh.belfry.core.model.World
import sh.belfry.core.model.WorldContext
import sh.belfry.core.net.Community
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.EventTemplate
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.nostr.Tag
import sh.belfry.core.nostr.withId
import sh.belfry.core.outbox.OutboxItem
import sh.belfry.core.outbox.OutboxStatus
import sh.belfry.core.outbox.Target
import sh.belfry.core.store.Store
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The demo: a made-up community seeded on the device, so every screen has something real-looking
 * to show (and the screenshot tests something stable to render). Keys are sha256 of names and
 * every time is relative to `now`, so two seeds with the same `now` give the same feed.
 * Nothing here ever touches the network.
 */
object Demo {
    const val DOMAIN = "belfry.example"
    const val NAME = "Riverside Commons"
    const val DEFAULT_NAME = "Alex Morgan"

    // channel ids
    const val GENERAL = "general"
    const val GARDEN = "garden-3f9a"
    const val KITCHEN = "kitchen-71c2"
    const val HELLO = "hello-b04e"
    const val WEBSITE = "website-5d1c"
    const val RANDOM = "random-9e77"
    const val POD_OPEN = "pod-reading-circle-a1b2"
    const val POD_CLOSED = "pod-finance-9f3c"
    const val EVENT_GROUP = "ws-e1a2b3c4"

    private fun key(name: String): LocalSigner = LocalSigner(sha256Hex("belfry-demo:" + name.lowercase()))

    /** The person using the demo. */
    fun me(name: String = DEFAULT_NAME): LocalSigner = key("me:" + name.trim())

    val maya = key("Maya Okafor")
    val tomas = key("Tomás Rivera")
    val lena = key("Lena Fischer")
    val joris = key("Joris de Vries")
    val aroha = key("Aroha Ngata")
    val sam = key("Sam Carter")
    val ines = key("Inès Dubois")
    val kofi = key("Kofi Mensah")
    val priya = key("guest Priya")
    val marcel = key("guest Marcel")
    val scout = key("guest Scout")
    val nora = key("guest Nora")
    /** Kofi's own agent (belfry.json `agents`): a member while he is one. */
    val wren = key("agent Wren")
    /** The demo's `_groups` key (posts e-mail cards and guest joins) and `_smtp` key (the mail bridge). */
    val groups = key("_groups")
    val bridge = key("_smtp")
    private val relayKey = key("relay").pubkey

    /** The demo community: Maya and the person using the demo are its admins. */
    fun community(me: String): Community = Community.defaults(DOMAIN).copy(name = NAME, admins = listOf(maya.pubkey, me), agents = mapOf(wren.pubkey to kofi.pubkey))

    private class Person(val key: LocalSigner, val name: String, val about: String, val tags: List<String>, val place: Int)

    /**
     * Fills STORE with the demo community as seen by ME at NOW (Unix seconds): events, a queued
     * and a refused outbox item, direct messages and e-mail (as gift wraps to ME), delivery
     * reports and what was read. The store should be empty.
     */
    suspend fun seed(store: Store, now: Long, me: LocalSigner, name: String = DEFAULT_NAME) {
        val events = ArrayList<Event>()
        val gr = "wss://groups-relay.$DOMAIN"
        val mr = "wss://relay.$DOMAIN"
        val h = 3600L
        val day = 86400L
        val myName = name.trim().ifEmpty { DEFAULT_NAME }
        val myUser = myName.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), ".").trim('.').ifEmpty { "me" }

        fun post(p: LocalSigner, at: Long, d: Draft): Event = p.signNow(EventTemplate(d.kind, d.content, d.tags, at)).also { events.add(it) }
        fun raw(p: LocalSigner, at: Long, kind: Int, tags: List<Tag>, content: String = ""): Event = p.signNow(EventTemplate(kind, content, tags, at)).also { events.add(it) }
        fun relay(kind: Int, at: Long, tags: List<Tag>): Event = Event("", relayKey, at, kind, tags, "").withId().also { events.add(it) }
        fun say(p: LocalSigner, g: String, at: Long, text: String, topic: String? = null, announce: Boolean = false): Event =
            post(p, at, Actions.postMessage(g, text, topic, announce))
        fun lists(id: String, at: Long, meta: List<Tag>, admins: List<LocalSigner>, members: List<LocalSigner>) {
            relay(39000, at, listOf(listOf("d", id)) + meta)
            relay(39001, at, listOf(listOf("d", id)) + admins.map { listOf("p", it.pubkey, "admin") })
            relay(39002, at, listOf(listOf("d", id)) + members.map { listOf("p", it.pubkey) })
        }
        fun channel(creator: LocalSigner, id: String, title: String, about: String, at: Long, admins: List<LocalSigner>, members: List<LocalSigner>) {
            Actions.createChannel(id, title, about).forEachIndexed { i, d -> post(creator, at + i, d) }
            lists(id, at + 1, listOf(listOf("name", title), listOf("about", about)), admins, members)
        }

        // ---- people -------------------------------------------------------------------------------
        val people = listOf(
            Person(me, myName, "Trying out the demo. I like **bread**, bikes and long tables.", listOf("baking", "bikes"), 0),
            Person(maya, "Maya Okafor", "Keeps the commons running. Ask me about the *garden* and the keys.", listOf("garden", "facilitation", "keys"), 0),
            Person(tomas, "Tomás Rivera", "Carpenter. I fix things on Saturdays.", listOf("repairs", "woodwork"), 4),
            Person(lena, "Lena Fischer", "Numbers person, slow reader, fast cyclist.", listOf("finance", "bikes", "reading"), 5),
            Person(joris, "Joris de Vries", "Photos, mostly of vegetables.", listOf("photography", "garden"), 2),
            Person(aroha, "Aroha Ngata", "Kai, compost and community dinners.", listOf("cooking", "compost", "garden"), 3),
            Person(sam, "Sam Carter", "New here. Bookkeeper by day.", listOf("finance", "baking"), 1),
            Person(ines, "Inès Dubois", "Runs the reading circle.", listOf("reading", "facilitation"), 0),
            Person(kofi, "Kofi Mensah", "Web and wires.", listOf("website", "repairs"), 1),
        )
        val members = people.map { it.key }
        val t0 = now - 20 * day
        people.forEachIndexed { i, p ->
            val user = if (p.key === me) myUser else p.name.lowercase(Locale.ROOT).substringBefore(' ').replace("á", "a").replace("è", "e")
            post(p.key, t0 + i, Actions.saveProfile(JsonObject(emptyMap()), p.name, p.about, "$user@$DOMAIN"))
            post(p.key, t0 + 100 + i, Actions.saveExtras(p.tags, Share.CITY, People.DEMO_PLACES[p.place]))
        }

        // ---- channels -----------------------------------------------------------------------------
        channel(maya, GENERAL, "general", "Everything about $NAME. Say hi 👋", t0 + 200, listOf(maya, me), members)
        channel(joris, GARDEN, "garden", "Beds, compost, seeds and **who waters when**.", t0 + 300, listOf(joris, maya), listOf(joris, maya, aroha, tomas, me, ines))
        channel(aroha, KITCHEN, "kitchen", "Community dinners and the shared pantry.", t0 + 400, listOf(aroha), listOf(aroha, me, sam, lena, kofi))
        channel(maya, HELLO, "hello", "Our front desk: e-mails to hello@$DOMAIN land here.", t0 + 500, listOf(maya, me), listOf(maya, me, lena, kofi))
        channel(kofi, WEBSITE, "website", "What is on $DOMAIN, and what should be.", t0 + 600, listOf(kofi), listOf(kofi, maya, me))
        channel(sam, RANDOM, "random", "Anything else.", t0 + 700, listOf(sam), listOf(sam, tomas, me, joris, kofi))

        // ---- #general: two days ago ------------------------------------------------------------
        val d2 = now - 2 * day - 3 * h
        val welcome = say(maya, GENERAL, d2, "Welcome to **$NAME**!\n\nA few things to know:\n- the workshop is open on Saturdays\n- dinners are on Thursdays, sign up in #kitchen\n- keys live with me, just ask", announce = true)
        val ladderOld = say(ines, GENERAL, d2 + 600, "The reading circle picked its next book: *The Dispossessed*. Six copies are on the shelf by the door.")
        say(tomas, GENERAL, d2 + 1500, "Workshop list for Saturday:\n1. fix the gate hinge\n2. sharpen the mower\n3. shelves for the pantry\n\nBring gloves. #repairs")
        say(tomas, GENERAL, d2 + 1560, "And if anyone has a `13 mm` spanner, I lost mine.")
        say(lena, GENERAL, d2 + 1700, "I have two. I'll bring both.")

        // ---- #general: yesterday -----------------------------------------------------------------
        val d1 = now - day - 5 * h
        val photoUrl = "https://picsum.photos/seed/belfry-garden/800/533.jpg"
        val photo = raw(joris, d1, 9, listOf(listOf("h", GENERAL), listOf("t", "garden"), listOf("imeta", "url $photoUrl", "m image/jpeg", "size 48211", "dim 800x533", "alt garden-beds.jpg")),
            "The new beds are in. Tomatoes go on the left 🌱 #garden\n$photoUrl")
        say(aroha, GENERAL, d1 + 400, "They look great. Compost delivery is on Friday, I need two people with wheelbarrows. #garden")
        val ladder = say(tomas, GENERAL, d1 + 2000, "Who can bring a ladder on Saturday? The tall one from the shed is broken.")
        post(maya, d1 + 2300, Actions.postThreadReply(GENERAL, ladder, "I have a 3 m one. Is that tall enough?"))
        post(tomas, d1 + 2400, Actions.postThreadReply(GENERAL, ladder, "Perfect, thanks!"))
        post(kofi, d1 + 2600, Actions.postThreadReply(GENERAL, ladder, "I can pick it up with the van on Friday evening."))
        post(me, d1 + 2900, Actions.postThreadReply(GENERAL, ladder, "I'll be there from 10 to help carry."))
        val mailPost = raw(lena, d1 + 5000, 9, listOf(listOf("h", GENERAL), listOf("subject", "Budget notes for March"), listOf("client", "belfry-mail")),
            "**Budget notes for March**\n\nWe spent 212 EUR on seeds and soil, and 80 EUR on the gate. The pantry shelves are next: about 150 EUR in wood.")
        // an event, a pod and a proposal, each announced by a card in #general
        val start = ((now + 2 * day) / day) * day + 18 * h
        val whenLabel = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH).withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(start))
        val (coord, eventDrafts) = Actions.createEvent(
            NewEvent(title = "Spring dinner", description = "Long table in the courtyard. Bring a dish to share and a chair if you can.", location = "The courtyard", start = start, end = start + 3 * h, online = "https://meet.jit.si/riverside-spring-dinner", tzid = "UTC", whenLabel = whenLabel),
            GENERAL, aroha.pubkey, gr, mr, "e1a2b3c4",
        )
        eventDrafts.forEachIndexed { i, d -> post(aroha, d1 + 6000 + i, d) }
        lists(EVENT_GROUP, d1 + 6001, listOf(listOf("name", "Spring dinner"), listOf("about", "Long table in the courtyard.")), listOf(aroha), listOf(aroha, maya, me, lena))
        Actions.createPod(POD_OPEN, "Reading circle", "One book a month, tea included.", GENERAL, PodAccess()).forEachIndexed { i, d -> post(ines, d1 + 7000 + i, d) }
        lists(POD_OPEN, d1 + 7001, listOf(listOf("name", "Reading circle"), listOf("about", "One book a month, tea included."), listOf("open")), listOf(ines), listOf(ines, lena, maya, me))
        val closed = PodAccess(true, "Why do you want to join, and what would you bring?")
        Actions.createPod(POD_CLOSED, "Finance", "Budgets and the yearly accounts. Members are admitted one by one.", GENERAL, closed).forEachIndexed { i, d -> post(me, d1 + 8000 + i, d) }
        lists(POD_CLOSED, d1 + 8001, listOf(listOf("name", "Finance"), listOf("about", "Budgets and the yearly accounts. Members are admitted one by one."), listOf("closed"), listOf("prompt", closed.prompt)), listOf(me), listOf(me, lena))
        post(sam, d1 + 9000, Actions.join(POD_CLOSED, "I keep the books for two associations and would like to help with the yearly accounts."))
        val (propGroup, propDrafts) = Actions.createProposal(
            NewProposal("Shared tool library", "Buy good tools once and lend them out from the shed",
                "## Why\nEveryone owns a drill that is used ten minutes a year.\n\n## What\n- a locked cabinet in the shed\n- a paper list and a channel to book tools\n- 400 EUR from the common budget\n\n## Who\nTomás and Kofi look after it.",
                ProposalStatus.VOTING),
            GENERAL, lena.pubkey, gr, mr, d1 + 10000, "a1b2c3",
        )
        val propEvents = propDrafts.mapIndexed { i, d -> post(lena, d1 + 10000 + i, d) }
        lists(propGroup, d1 + 10001, listOf(listOf("name", "Shared tool library"), listOf("about", "Buy good tools once and lend them out from the shed")), listOf(lena), listOf(lena, tomas, kofi, me, maya))
        val poll = propEvents.first { it.kind == 1068 }
        for ((voter, choice) in listOf(maya to "yes", tomas to "yes", kofi to "yes", aroha to "abstain", sam to "no"))
            raw(voter, d1 + 11000, 1018, listOf(listOf("h", propGroup), listOf("e", poll.id), listOf("response", choice)))
        say(tomas, propGroup, d1 + 11500, "Yes from me. I can build the cabinet from the old shelves.")
        say(sam, propGroup, d1 + 11800, "400 EUR feels like a lot before we know who will use it. Could we start with 200?")

        // ---- #general: today ---------------------------------------------------------------------
        val t = now - 6 * h
        // guests: one who saved their account, one who has not, an agent, and one who became a member
        fun join(guest: LocalSigner, guestName: String, by: LocalSigner, at: Long, extra: List<Tag> = emptyList()) =
            raw(groups, at, 9000, listOf(listOf("h", GENERAL), listOf("p", guest.pubkey, "member"), listOf("name", guestName), listOf("guest", "2099-12-31"), listOf("invited-by", by.pubkey)) + extra)
        fun label(guest: LocalSigner, value: String, at: Long) =
            raw(groups, at, 1985, listOf(listOf("h", GENERAL), listOf("L", World.GUEST_NS), listOf("l", value, World.GUEST_NS), listOf("p", guest.pubkey)))
        join(nora, "Nora Lindqvist", maya, now - 9 * day, listOf(listOf("unsaved", "1")))
        label(nora, "member", now - 4 * day)
        join(priya, "Priya Nair", lena, t, listOf(listOf("unsaved", "1")))
        label(priya, "saved", t + 200)
        say(priya, GENERAL, t + 300, "Hi all, Lena invited me. I'm here for the dinner on Thursday 👋")
        val review = say(sam, GENERAL, t + 900, "nostr:${Bech32.npub(me.pubkey)} could you look at the pantry budget before Friday? Lena's notes are above.")
        val mine = say(me, GENERAL, t + 1500, "Yes, tonight. Are the shelves in or out of the budget?")
        say(me, GENERAL, t + 1530, "I'll post my notes in the Finance pod.")
        join(scout, "Scout", kofi, t + 3000, listOf(listOf("unsaved", "1"), listOf("agent", "1")))
        // an older thread comes back up: a reply today to Inès's message of two days ago
        post(lena, t + 4000, Actions.postThreadReply(GENERAL, ladderOld, "I finished it last night. Can we move the circle to next week?"))
        post(ines, t + 4200, Actions.postThreadReply(GENERAL, ladderOld, "Next Wednesday then, same place."))
        join(marcel, "Marcel", me, t + 6000, listOf(listOf("unsaved", "1")))
        // Kofi's own agent joins (a member's agent: labelled member at once) and links a channel
        post(wren, t + 6100, Actions.saveProfile(JsonObject(emptyMap()), "Wren", "Kofi's agent. I keep the pantry list.", null))
        join(wren, "Wren", kofi, t + 6200, listOf(listOf("agent", "1")))
        label(wren, "member", t + 6200)
        val kitchenRef = "nostr:" + Nip19.naddr(Nip19.Addr(39000, relayKey, KITCHEN, listOf(gr)))
        say(wren, GENERAL, t + 6400, "Pantry list is updated: rice and lentils are low. Details in $kitchenRef")
        say(maya, GENERAL, now - 40 * 60, "Reminder: the gate code changes on Monday. I'll send it by direct message.")

        // reactions, an edit, pinned topics, seen
        for ((who, emoji) in listOf(tomas to "🎉", lena to "🎉", aroha to "❤️", me to "🎉", kofi to "👍")) post(who, d2 + 100, Actions.react(GENERAL, welcome.id, maya.pubkey, emoji))
        for ((who, emoji) in listOf(maya to "❤️", aroha to "❤️", ines to "😂")) post(who, d1 + 200, Actions.react(GENERAL, photo.id, joris.pubkey, emoji))
        post(sam, t + 1600, Actions.react(GENERAL, mine.id, me.pubkey, "🙏"))
        post(me, t + 950, Actions.react(GENERAL, review.id, sam.pubkey, "👀"))
        post(tomas, d1 + 5100, Actions.react(GENERAL, mailPost.id, lena.pubkey, "👍"))
        val soFar = World(events, emptyList(), WorldContext(me.pubkey, DOMAIN, NAME, setOf(maya.pubkey, me.pubkey), groups.pubkey, bridge.pubkey, ZoneOffset.UTC))
        soFar.message(GENERAL, mine.id)?.let { post(me, t + 1700, Actions.editMessage(GENERAL, it, "Yes, tonight. Are the pantry shelves in or out of the budget?")) }
        post(maya, d2 + 50, Actions.setTopics(GENERAL, listOf("garden", "repairs")))
        for ((i, p) in listOf(maya, tomas, lena, joris, aroha, sam, ines, kofi).withIndex()) post(p, now - (i + 1) * 2 * h, Actions.seen(GENERAL))
        post(me, now - 7 * h, Actions.seen(GENERAL))
        // the event's RSVPs
        soFar.calEvent(coord)?.let { cal ->
            post(maya, d1 + 6100, Actions.rsvp(cal, Rsvp.ACCEPTED, maya.pubkey))
            post(lena, d1 + 6200, Actions.rsvp(cal, Rsvp.ACCEPTED, lena.pubkey))
            post(tomas, d1 + 6300, Actions.rsvp(cal, Rsvp.ACCEPTED, tomas.pubkey))
            post(joris, d1 + 6400, Actions.rsvp(cal, Rsvp.TENTATIVE, joris.pubkey))
            post(kofi, d1 + 6500, Actions.rsvp(cal, Rsvp.DECLINED, kofi.pubkey))
        }
        say(aroha, EVENT_GROUP, d1 + 6600, "I'll make a big pot of dal. Who brings bread?")
        say(maya, EVENT_GROUP, d1 + 6700, "Bread and butter from me.")
        // a drop box link for photos
        post(me, now - 3 * h, Actions.createDropLink(GENERAL, 7.0, false, now - 3 * h, "demoDropLinkToken0123").second)

        // ---- the other channels ----------------------------------------------------------------
        say(joris, GARDEN, now - 3 * day, "Watering rota for this week:\n- Mon/Tue: Joris\n- Wed/Thu: Aroha\n- Fri to Sun: whoever is at the workshop")
        val compost = say(aroha, GARDEN, now - day - 2 * h, "The compost bin on the left is full. Use the right one until Friday.")
        // a pinned notice and a to-do made from a message (#garden)
        val rota = events.last { it.kind == 9 && it.pubkey == joris.pubkey && it.tag("h") == GARDEN }
        post(joris, now - 3 * day + 120, Actions.pinMessage(GARDEN, rota.id, true))
        post(maya, now - day, Actions.addTask(GARDEN, "Empty the left compost bin", compost.id))
        post(tomas, now - 20 * h, Actions.addTask(GARDEN, "Mend the greenhouse door"))
        say(maya, GARDEN, now - 5 * h, "Seed swap this Sunday at 11, by the greenhouse. #seeds")
        say(aroha, KITCHEN, now - 2 * day, "Thursday dinner: dal, rice, flatbread. **12 seats**, 7 taken.")
        say(sam, KITCHEN, now - day, "Count me in. I can do the dishes.")
        say(lena, KITCHEN, now - 90 * 60, "Put me down for two seats, please.")
        post(aroha, now - 2 * day + 60, Actions.addTask(KITCHEN, "Buy rice and lentils"))
        post(aroha, now - 2 * day + 61, Actions.addTask(KITCHEN, "Borrow the big pot from the school"))
        say(kofi, WEBSITE, now - 4 * day, "The new homepage is up as a preview. Opening hours are still wrong, I know.")
        say(maya, WEBSITE, now - 4 * day + 900, "Looks good. Can the dinner sign-up be on the first page?")
        say(sam, RANDOM, now - 6 * day, "Found a blue bike helmet by the gate. It's on the shelf in the hall.")
        say(tomas, RANDOM, now - 26 * h, "Does anyone want a box of *very* ripe pears?")
        say(ines, POD_OPEN, now - 8 * h, "Chapter 1 to 4 for next time. Tea is on me.")
        say(lena, POD_CLOSED, now - 30 * h, "Draft accounts for last year are in the shared folder. Two receipts are missing.")

        // ---- #hello: a shared inbox -----------------------------------------------------------
        post(maya, t0 + 510, Actions.setInbox(HELLO, true))
        fun card(at: Long, from: String, fromName: String, subject: String, body: String, id: String, verified: Boolean): Event =
            raw(groups, at, 9, listOf(listOf("h", HELLO), listOf("subject", subject), listOf("mail-from", from), listOf("mail-name", fromName), listOf("message-id", id),
                listOf("belfry-auth", if (verified) "pass" else "none"), listOf("client", "belfry-mail")), "**$subject**\n\n$body")
        val c1 = card(now - 28 * h, "noor@school.example", "Noor El Amrani", "Visit with a school class", "Hello, I teach a class of 24 children (age 9). Could we visit the garden one morning in May? We would bring our own gloves.", "<visit-24@school.example>", true)
        raw(groups, now - 26 * h, 9, listOf(listOf("h", HELLO), listOf("subject", "Re: Visit with a school class"), listOf("mail-to", "noor@school.example"), listOf("sent-by", maya.pubkey), listOf("e", c1.id, "", "reply"), listOf("client", "belfry-mail-out")),
            "Dear Noor, with pleasure. Tuesday or Thursday mornings work best. How about 12 May at 9:30?")
        say(lena, HELLO, now - 25 * h, "I can show them the compost. Kids love the worms.")
        card(now - 2 * h, "delivery@gardenshop.example", "The Garden Shop", "Your order is on its way", "Your order of 6 bags of potting soil will be delivered on Friday between 8 and 12. Someone needs to be there to sign.", "<order-7781@gardenshop.example>", false)

        store.addEvents(events)
        store.set(Session.K_GROUPS_KEY, groups.pubkey)
        store.set(Session.K_BRIDGE, bridge.pubkey)

        // ---- the outbox: one waiting, one refused ------------------------------------------------
        store.putOutbox(OutboxItem("local-demo-refused", Target.GROUPS, 9, "Sent from the train, twice by accident.", listOf(listOf("h", GENERAL), Actions.CLIENT_TAG), now - 20 * 60,
            status = OutboxStatus.FAILED, error = "blocked: you are posting too fast", tries = 1))
        store.putOutbox(OutboxItem("local-demo-waiting", Target.GROUPS, 9, "On my way, see you at the gate.", listOf(listOf("h", GENERAL), listOf("demo", "hold"), Actions.CLIENT_TAG), now - 5 * 60, tries = 1))

        // ---- direct messages and e-mail (gift wraps to me) ---------------------------------------
        val wraps = ArrayList<Event>()
        suspend fun dm(from: LocalSigner, to: List<LocalSigner>, at: Long, text: String) {
            val rumor = GiftWrap.rumor(14, from.pubkey, to.filter { it !== from }.map { listOf("p", it.pubkey) }, text, at)
            wraps.add(GiftWrap.wrap(rumor, me.pubkey, from))
        }
        dm(maya, listOf(me), now - 26 * h, "Can you open the workshop on Saturday? I'm away until noon.")
        dm(me, listOf(maya), now - 25 * h, "Sure. Where is the key these days?")
        dm(maya, listOf(me), now - 25 * h + 300, "In the **blue box** behind the seed shelf.")
        dm(maya, listOf(me), now - 35 * 60, "The new gate code is 4417. Please don't post it in a channel 🙏")
        val trio = listOf(tomas, lena, me)
        dm(tomas, trio, now - 3 * day, "Shall we buy the wood for the pantry shelves together?")
        dm(lena, trio, now - 3 * day + 600, "Yes. The budget allows 150 EUR.")
        dm(me, trio, now - 3 * day + 900, "I can drive on Friday afternoon.")
        dm(me, listOf(me), now - 4 * day, "Note to self: bring the 13 mm spanner back to Tomás.")

        val myAddress = "$myUser@$DOMAIN"
        suspend fun mailIn(at: Long, sender: String, address: String, subject: String, text: String, id: String, verified: Boolean) {
            val body = "From: \"$sender\" <$address>\r\nTo: <$myAddress>\r\nSubject: $subject\r\nMessage-ID: $id\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n$text\r\n"
            val rumor = GiftWrap.rumor(1301, bridge.pubkey, listOf(listOf("email-id", "demo-in-" + sha256Hex(id).take(12)), listOf("mail-from", address), listOf("p", me.pubkey),
                listOf("subject", subject), listOf("belfry-auth", if (verified) "pass" else "none")), body, at)
            wraps.add(GiftWrap.wrap(rumor, me.pubkey, bridge))
        }
        mailIn(now - 5 * h, "Ana Pereira", "ana@example.org", "Visiting next week", "Hi! I'm in town next week and would love to see the commons. Is Wednesday afternoon a good time?\n\nAna", "<visit-1@example.org>", true)
        val answer = Mime.buildMail(myName, myAddress, "ana@example.org", "Re: Visiting next week", "Hi Ana, Wednesday works. Come by at 3, I'll show you around.", DOMAIN, "<visit-1@example.org>", null, now - 4 * h)
        val sentId = "demo-email-1"
        val sent = GiftWrap.rumor(1301, me.pubkey, listOf(listOf("email-id", sentId), listOf("mail-from", myAddress), listOf("rcpt-to", "ana@example.org"), listOf("subject", "Re: Visiting next week")), answer.raw, now - 4 * h)
        wraps.add(GiftWrap.wrap(sent, me.pubkey, me))
        mailIn(now - 50 * 60, "City Office", "permits@cityhall.example", "Street party permit", "Your request for a street party on 14 June was received. Please send the list of neighbours who agreed before 1 May.", "<permit-88@cityhall.example>", false)
        store.addEvents(wraps)

        store.set(DirectMessages.K_REPORTS, buildJsonObject {
            put(sentId, buildJsonObject { put("status", JsonPrimitive("delivered")); put("details", JsonPrimitive("")) })
        }.toString())
        store.set(DirectMessages.K_READ, buildJsonObject {
            put(MailThreads.nostrConv(tomas.pubkey, listOf(lena.pubkey, me.pubkey), me.pubkey), JsonPrimitive(now))
            put("n:" + me.pubkey, JsonPrimitive(now))
            put(MailThreads.mailThreadOf("ana@example.org", "Visiting next week"), JsonPrimitive(now))
        }.toString())
    }
}
