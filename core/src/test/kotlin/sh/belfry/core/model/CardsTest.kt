package sh.belfry.core.model

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import sh.belfry.core.engine.DemoCards
import sh.belfry.core.engine.GuestInfo
import sh.belfry.core.engine.SavedSession
import sh.belfry.core.net.Community
import sh.belfry.core.net.InviteScan
import sh.belfry.core.net.cardOf
import sh.belfry.core.nostr.parseJsonOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Printed cards, the pure rules (SPEC 19.72–19.74): a port of the web's `core/cards.ts` at chat f10e6b2. */
class CardsTest {
    private fun obj(s: String) = parseJsonOrNull(s) as JsonObject
    private val invite = "AbCdEfGhIjKlMnOpQrStUvWx_-12"

    @Test fun theAddress() {
        assertEquals(CardLink("belfry.brussels", "K7Q2MX"), CardLink.parse("https://chat.belfry.brussels/g/K7Q2MX"))
        // lower case is accepted and read as upper case; one trailing slash; the web's #/g/
        assertEquals(CardLink("belfry.sh", "K7Q2MX"), CardLink.parse("  https://chat.belfry.sh/g/k7q2mx/\n"))
        assertEquals(CardLink("belfry.sh", "K7Q2MX"), CardLink.parse("https://chat.belfry.sh/#/g/K7q2Mx"))
        assertTrue(CardLink.parse("https://chat.belfry.sh/g/K7Q2MX")!!.known)
        // an address of a card whose id is not one: "This card isn't known here", never an error about the address
        for (bad in listOf("K7Q2M", "K7Q2MXX", "K7Q2M0", "K7Q2MO", "K7Q2MI", "K7Q2ML", "K7Q2M1", "K7 2MX", "", "a/b")) {
            val l = CardLink.parse("https://chat.belfry.sh/g/$bad")
            assertEquals(CardLink("belfry.sh", ""), l, bad)
            assertFalse(l!!.known)
        }
        // not a card's address at all
        for (no in listOf(
            "http://chat.belfry.sh/g/K7Q2MX", "https://belfry.sh/g/K7Q2MX", "https://chat.belfry.sh/g/K7Q2MX?x=1", "https://chat.belfry.sh/g/K7Q2MX#frag",
            "https://chat.belfry.sh@evil.example/g/K7Q2MX", "https://chat.belfry.sh/join/$invite", "https://chat.belfry.sh/group/K7Q2MX", "hello", "",
        )) assertNull(CardLink.parse(no), no)
        assertEquals("https://chat.belfry.sh/g/K7Q2MX", Cards.url("belfry.sh", "K7Q2MX"))
    }

    @Test fun scannedPastedAndTappedInAChat() {
        assertEquals(CardLink("belfry.sh", "T9PC4H"), InviteScan.card("https://chat.belfry.sh/g/t9pc4h"))
        assertNull(InviteScan.card(null))
        assertNull(InviteScan.card("https://chat.belfry.sh/g/T9PC4H" + "x".repeat(600)))
        assertNull(InviteScan.read("https://chat.belfry.sh/g/T9PC4H"))
        assertEquals(Links.InApp.Card("https://chat.belfry.sh/g/T9PC4H"), Links.inApp("https://chat.belfry.sh/g/T9PC4H", "belfry.sh"))
        assertNull(Links.inApp("https://chat.belfry.sh/g/T9PC4H", "enspiral.eu"))
    }

    @Test fun ids() {
        assertTrue(Cards.isId("K7Q2MX"))
        assertTrue(Cards.isId("ABCDEF"))
        assertTrue(Cards.isId("234567"))
        for (bad in listOf("k7q2mx", "K7Q2M", "K7Q2MXA", "K0Q2MX", "KOQ2MX", "K1Q2MX", "KIQ2MX", "KLQ2MX", "K7Q2M-")) assertFalse(Cards.isId(bad), bad)
        assertEquals("K7Q2MX", Cards.cleanId("k7q2mx"))
        assertEquals("K7Q2MX", Cards.cleanId("K7Q2MX/"))
        assertNull(Cards.cleanId("K7Q2MX//"))
        assertNull(Cards.cleanId("unknown"))
    }

    @Test fun theHashtagFromTheTopic() {
        assertEquals("tool-library", Cards.hashtagSlug("Tool library"))
        assertEquals("tool-library", Cards.hashtagSlug("  #Tool Library  "))
        assertEquals("tool---library", Cards.hashtagSlug("##tool---library"))
        assertEquals("gluten-free-baking", Cards.hashtagSlug("Gluten-free baking!"))
        assertEquals("caf-s-bars", Cards.hashtagSlug("Cafés & bars"))
        assertEquals("a", Cards.hashtagSlug("a"))
        assertEquals("", Cards.hashtagSlug("  !!  "))
        assertEquals("", Cards.hashtagSlug(""))
        // 30 at most, cut after the dashes at both ends were dropped (as the web cuts)
        assertEquals("a".repeat(30), Cards.hashtagSlug("a".repeat(40)))
        assertEquals(30, Cards.hashtagSlug("one two three four five six seven eight nine").length)
    }

    @Test fun theNextFreeOneWhenTaken() = runBlocking<Unit> {
        assertEquals("tools-2", Cards.nextHashtag("tools", 2))
        // a cut never leaves a dash at the end, nor two before the number
        assertEquals("a".repeat(29), Cards.hashtagSlug("a".repeat(29) + " bcd"))
        assertEquals("a".repeat(27) + "-2", Cards.nextHashtag("a".repeat(27) + "-bc", 2))
        assertEquals("a".repeat(28) + "-9", Cards.nextHashtag("a".repeat(30), 9))
        assertEquals(30, Cards.nextHashtag("a".repeat(30), 2).length)

        val asked = ArrayList<String>()
        fun check(taken: Set<String>, unknown: Set<String> = emptySet()): suspend (String) -> HashtagState = { h ->
            asked.add(h)
            if (h in unknown) HashtagState.UNKNOWN else if (h in taken) HashtagState.TAKEN else HashtagState.FREE
        }
        assertEquals("general-2", Cards.nextFree("general", check(setOf("general"))))
        asked.clear()
        assertEquals("general-4", Cards.nextFree("general", check(setOf("general", "general-2", "general-3"))))
        assertEquals(listOf("general-2", "general-3", "general-4"), asked)
        // every one to -9 taken: nothing is offered
        assertNull(Cards.nextFree("general", check((2..9).map { "general-$it" }.toSet())))
        // the belfry can't check: nothing is offered, and nothing more is asked
        asked.clear()
        assertNull(Cards.nextFree("general", check(setOf("general-2"), unknown = setOf("general-3"))))
        assertEquals(listOf("general-2", "general-3"), asked)
    }

    @Test fun aHashtagsAnswerIsNeverFreeWhenTheBelfryCouldNotCheck() {
        assertEquals(HashtagState.FREE, Cards.hashtagAnswer(200, obj("""{"state":"free"}""")))
        assertEquals(HashtagState.TAKEN, Cards.hashtagAnswer(200, obj("""{"state":"taken"}""")))
        assertEquals(HashtagState.INVALID, Cards.hashtagAnswer(200, obj("""{"state":"invalid"}""")))
        assertEquals(HashtagState.INVALID, Cards.hashtagAnswer(200, null))
        assertEquals(HashtagState.UNKNOWN, Cards.hashtagAnswer(503, obj("""{"error":"relay"}""")))
        // a 503 that says "free" all the same is not free
        assertEquals(HashtagState.UNKNOWN, Cards.hashtagAnswer(503, obj("""{"state":"free"}""")))
        assertEquals(HashtagState.UNKNOWN, Cards.hashtagAnswer(500, null))
        assertEquals(HashtagState.UNKNOWN, Cards.hashtagAnswer(429, obj("""{"error":"too_many"}""")))

        assertEquals("✓ Available · suggested from the topic, you can change it", Cards.hashtagLine(HashtagState.FREE, checking = false, edited = false))
        assertEquals("✓ Available", Cards.hashtagLine(HashtagState.FREE, checking = false, edited = true))
        assertEquals("Taken: try another one", Cards.hashtagLine(HashtagState.TAKEN, false, false))
        assertEquals("Letters, digits and dashes, 2 to 30", Cards.hashtagLine(HashtagState.INVALID, false, true))
        assertEquals("Couldn’t check just now", Cards.hashtagLine(HashtagState.UNKNOWN, false, true))
        assertEquals("Checking…", Cards.hashtagLine(HashtagState.FREE, checking = true, edited = false))
        assertNull(Cards.hashtagLine(null, false, false))
    }

    @Test fun whatACardIs() {
        assertEquals(CardInfo.Unclaimed(CardKind.GROUP), Cards.infoOf(obj("""{"kind":"group","state":"unclaimed"}""")))
        assertEquals(CardInfo.Unclaimed(CardKind.TOPIC), Cards.infoOf(obj("""{"kind":"topic","state":"unclaimed"}""")))
        assertEquals(
            CardInfo.Claimed(CardKind.GROUP, "pod-sourdough-ab12", "Sourdough starters", invite),
            Cards.infoOf(obj("""{"kind":"group","state":"claimed","group":"pod-sourdough-ab12","title":" Sourdough‮ starters ","invite":"$invite"}""")),
        )
        // a claimed card without a usable group or invite is not one
        assertEquals(CardInfo.Unknown, Cards.infoOf(obj("""{"kind":"group","state":"claimed","group":"pod-x","title":"X"}""")))
        assertEquals(CardInfo.Unknown, Cards.infoOf(obj("""{"kind":"group","state":"claimed","group":"a b","title":"X","invite":"$invite"}""")))
        assertEquals(CardInfo.Unknown, Cards.infoOf(obj("""{"kind":"group","state":"claimed","group":"pod-x","title":"X","invite":"short"}""")))
        assertEquals(CardInfo.Retired(CardKind.GROUP, ""), Cards.infoOf(obj("""{"kind":"group","state":"retired","title":""}""")))
        assertEquals(CardInfo.Archived(CardKind.TOPIC, "tool-library"), Cards.infoOf(obj("""{"kind":"topic","state":"archived","title":"tool-library"}""")))
        assertEquals(CardInfo.Unknown, Cards.infoOf(obj("""{"kind":"group","state":"lost"}""")))
        assertEquals(CardInfo.Unknown, Cards.infoOf(obj("{}")))
        // a title is cut to 80
        assertEquals(80, (Cards.infoOf(obj("""{"state":"retired","title":"${"x".repeat(300)}"}""")) as CardInfo.Retired).title.length)
    }

    @Test fun refusalsByCode() {
        assertEquals(CardError.UNKNOWN, Cards.failure(404, obj("""{"error":"not_found"}""")).code)
        assertEquals(CardError.TOO_MANY, Cards.failure(429, null).code)
        assertEquals(CardError.SIGNER, Cards.failure(503, obj("""{"error":"relay"}""")).code)
        assertEquals(CardError.SIGNER, Cards.failure(503, obj("""{"error":"signer"}""")).code)
        assertEquals(CardError.UNSUPPORTED, Cards.failure(409, obj("""{"error":"unsupported"}""")).code)
        assertEquals(CardError.FAILED, Cards.failure(500, obj("""{"error":"boom"}""")).code)
        assertEquals(CardError.FAILED, Cards.failure(401, obj("""{"error":"unauthorized"}""")).code)
        assertEquals(CardError.FAILED, Cards.failure(502, null).code)
        for ((e, c) in listOf(
            "claimed" to CardError.CLAIMED, "retired" to CardError.RETIRED, "no_pass" to CardError.NO_PASS, "guest_not_saved" to CardError.GUEST_NOT_SAVED,
            "unverified" to CardError.UNVERIFIED, "hashtag_taken" to CardError.HASHTAG_TAKEN, "own_key" to CardError.OWN_KEY, "agent" to CardError.AGENT,
            "key_taken" to CardError.KEY_TAKEN, "wrong_code" to CardError.WRONG_CODE, "expired" to CardError.EXPIRED, "invalid" to CardError.INVALID, "too_many" to CardError.TOO_MANY,
        )) assertEquals(c, Cards.failure(409, obj("""{"error":"$e","message":"the server's own words"}""")).code, e)

        val claimed = Cards.failure(409, obj("""{"error":"claimed","group":"pod-sourdough-ab12","invite":"$invite"}"""))
        assertEquals("pod-sourdough-ab12", claimed.group)
        assertEquals(invite, claimed.invite)
        // only checked values are kept
        val odd = Cards.failure(409, obj("""{"error":"claimed","group":"a b","invite":"x","field":"Title!","tries_left":"3"}"""))
        assertNull(odd.group); assertNull(odd.invite); assertNull(odd.field); assertNull(odd.triesLeft)
        assertEquals(3, Cards.failure(400, obj("""{"error":"wrong_code","tries_left":3}""")).triesLeft)
        assertNull(Cards.failure(400, obj("""{"error":"wrong_code","tries_left":100}""")).triesLeft)
        assertNull(Cards.failure(400, obj("""{"error":"wrong_code","tries_left":-1}""")).triesLeft)
        assertEquals("hashtag", Cards.failure(422, obj("""{"error":"invalid","field":"hashtag"}""")).field)
    }

    @Test fun everyRefusalInTheAppsOwnWords() {
        fun t(code: CardError, kind: CardKind = CardKind.GROUP) = Cards.errorText(CardFailed(code), kind)
        assertEquals("That code is not right. 3 tries left.", Cards.errorText(CardFailed(CardError.WRONG_CODE, triesLeft = 3)))
        assertEquals("That code is not right. 1 try left.", Cards.errorText(CardFailed(CardError.WRONG_CODE, triesLeft = 1)))
        assertEquals("That code is not right. Send a new one.", Cards.errorText(CardFailed(CardError.WRONG_CODE, triesLeft = 0)))
        assertEquals("That code is not right. Check the e-mail and try again.", t(CardError.WRONG_CODE))
        assertEquals("That doesn’t look like an e-mail address.", Cards.errorText(CardFailed(CardError.INVALID, field = "email")))
        assertEquals("Use letters, digits and dashes, 2 to 30 characters.", Cards.errorText(CardFailed(CardError.INVALID, field = "hashtag")))
        assertEquals("Please give your name.", Cards.errorText(CardFailed(CardError.INVALID, field = "name")))
        assertEquals("Please give the group’s name, up to 80 characters.", Cards.errorText(CardFailed(CardError.INVALID, field = "title")))
        assertEquals("Please give the topic’s name, up to 80 characters.", Cards.errorText(CardFailed(CardError.INVALID), CardKind.TOPIC))
        assertEquals("Someone just started this group.", t(CardError.CLAIMED))
        assertEquals("Someone just started this topic.", t(CardError.CLAIMED, CardKind.TOPIC))
        assertEquals("This card was retired: it doesn’t start a group any more.", t(CardError.RETIRED))
        assertEquals("Only members of this belfry can start a topic while signed in. Sign out to start it as a guest with your e-mail.", t(CardError.NO_PASS, CardKind.TOPIC))
        assertEquals("Save your account with your e-mail first, then scan the card again.", t(CardError.GUEST_NOT_SAVED))
        assertEquals("Confirm your e-mail address first, then scan the card again.", t(CardError.UNVERIFIED))
        assertEquals("This hashtag is taken. Try another one.", t(CardError.HASHTAG_TAKEN))
        assertEquals("This address has an account that signs in with its own key. Sign in with it, then scan the card again.", t(CardError.OWN_KEY))
        assertEquals("An AI agent can’t start it. Use a person’s account.", t(CardError.AGENT))
        assertEquals("That did not work. Send a new code and try again.", t(CardError.KEY_TAKEN))
        assertEquals("That code has expired. Send a new one.", t(CardError.EXPIRED))
        assertEquals("Too many tries for now. Try again in a few minutes.", t(CardError.TOO_MANY))
        assertEquals("The belfry is busy. Try again in a minute.", t(CardError.SIGNER))
        // on the code screen a busy belfry has spent the code (docs/cards.md §2.3)
        assertEquals("The belfry is busy. Send a new code in a minute and try again.", Cards.errorText(CardFailed(CardError.SIGNER), afterCode = true))
        assertEquals("This card isn’t known here.", t(CardError.UNKNOWN))
        assertEquals("This belfry doesn’t take printed cards.", t(CardError.UNSUPPORTED))
        assertEquals("No connection. Try again.", t(CardError.OFFLINE))
        assertEquals("That did not work. Try again.", t(CardError.FAILED))
        // every code has words
        for (c in CardError.entries) for (k in CardKind.entries) assertTrue(Cards.errorText(CardFailed(c), k).isNotBlank())
        // which refusals of the code empty the boxes
        assertEquals(setOf(CardError.WRONG_CODE, CardError.EXPIRED, CardError.KEY_TAKEN, CardError.SIGNER), CardError.entries.filter(Cards::clearsCode).toSet())
    }

    @Test fun whenStartMayBeTapped() {
        val f = CardForm(title = "Sourdough starters", name = "Lou", email = "lou@example.org")
        assertTrue(Cards.ready(CardKind.GROUP, f, signedIn = false, tag = null))
        assertTrue(Cards.ready(CardKind.GROUP, CardForm(title = "x"), signedIn = true, tag = null))
        assertFalse(Cards.ready(CardKind.GROUP, CardForm(title = "  "), signedIn = true, tag = null))
        assertFalse(Cards.ready(CardKind.GROUP, f.copy(name = " "), signedIn = false, tag = null))
        assertFalse(Cards.ready(CardKind.GROUP, f.copy(email = "lou@example"), signedIn = false, tag = null))
        assertFalse(Cards.ready(CardKind.GROUP, f.copy(email = "lou example.org"), signedIn = false, tag = null))
        // a topic needs its hashtag checked free: never on "couldn't check", never while unchecked
        for (s in listOf(null, HashtagState.TAKEN, HashtagState.INVALID, HashtagState.UNKNOWN)) assertFalse(Cards.ready(CardKind.TOPIC, f, false, s), "$s")
        assertTrue(Cards.ready(CardKind.TOPIC, f, false, HashtagState.FREE))

        assertFalse(Cards.hashtagEdited(CardForm(title = "Tool library", hashtag = "tool-library")))
        assertTrue(Cards.hashtagEdited(CardForm(title = "Tool library", hashtag = "tools")))
        assertFalse(Cards.hashtagEdited(CardForm(title = "Tool library")))
    }

    @Test fun theBodies() {
        val f = CardForm(title = "  Tool\u0007 library ", about = " Borrow drills. ", hashtag = "#Tool-Library", name = " Dev‮ ", email = " dev@example.org ")
        assertEquals("""{"title":"Tool library","about":"Borrow drills."}""", Cards.claimBody(CardKind.GROUP, f).toString())
        // a topic card: its hashtag and the summary by e-mail, weekly unless monthly was picked
        assertEquals("""{"title":"Tool library","about":"Borrow drills.","hashtag":"tool-library","summary":"weekly"}""", Cards.claimBody(CardKind.TOPIC, f).toString())
        assertEquals("""{"title":"Tool library","about":"Borrow drills.","hashtag":"tool-library","summary":"monthly"}""", Cards.claimBody(CardKind.TOPIC, f.copy(summary = "monthly")).toString())
        assertEquals("""{"title":"Tool library","about":"Borrow drills.","hashtag":"tool-library","summary":"weekly"}""", Cards.claimBody(CardKind.TOPIC, f.copy(summary = "daily")).toString())
        assertEquals(
            """{"email":"dev@example.org","code":"123456","name":"Dev","client":"belfry-android","title":"Tool library","about":"Borrow drills.","hashtag":"tool-library","summary":"monthly"}""",
            Cards.verifyBody(CardKind.TOPIC, f.copy(summary = "monthly"), " 123456 ").toString(),
        )
        // never on a group card, whatever the form holds
        assertFalse(Cards.verifyBody(CardKind.GROUP, f.copy(summary = "monthly"), "123456").containsKey("hashtag"))
        assertFalse(Cards.verifyBody(CardKind.GROUP, f.copy(summary = "monthly"), "123456").containsKey("summary"))
        assertFalse(Cards.claimBody(CardKind.GROUP, f.copy(summary = "monthly")).containsKey("summary"))
        assertEquals("What was said, once a week. Change it any time.", Cards.summaryHint("weekly"))
        assertEquals("What was said, once a month. Change it any time.", Cards.summaryHint("monthly"))
        assertEquals("monthly", CardForm.of(CardForm(summary = "monthly").toJson())!!.summary)
        assertEquals("weekly", CardForm.of(obj("""{"summary":"yearly"}"""))!!.summary)
        assertEquals("weekly", CardForm().summary)
        assertEquals(80, (Cards.claimBody(CardKind.GROUP, CardForm(title = "x".repeat(200)))["title"] as kotlinx.serialization.json.JsonPrimitive).content.length)
        assertEquals("Tool library · Borrow drills.", Cards.topicAbout(f))
        assertEquals("Tool library", Cards.topicAbout(f.copy(about = " ")))
        // the kept form: every field back, cut to its length
        assertEquals(CardForm("a", "b", "c", "d", "e@f.g"), CardForm.of(CardForm("a", "b", "c", "d", "e@f.g").toJson()))
        assertEquals(80, CardForm.of(obj("""{"title":"${"x".repeat(100)}"}"""))!!.title.length)
        assertNull(CardForm.of(null))
    }

    @Test fun theWordsOfTheScreens() {
        assertEquals("Start this group", Cards.startTitle(CardKind.GROUP))
        assertEquals("Start the topic", Cards.startButton(CardKind.TOPIC))
        assertEquals("Topic", Cards.titleLabel(CardKind.TOPIC))
        assertEquals("Title", Cards.titleLabel(CardKind.GROUP))
        assertEquals("Join", Cards.takenButton(CardKind.GROUP))
        assertEquals("Open the topic", Cards.takenButton(CardKind.TOPIC))
        assertEquals("Sourdough starters is closed to new people", Cards.retiredTitle(CardKind.GROUP, "Sourdough starters"))
        assertEquals("This topic is closed to new people", Cards.retiredTitle(CardKind.TOPIC, ""))
        assertEquals("Book club has ended", Cards.archivedTitle(CardKind.GROUP, "Book club"))
        assertEquals("Check that you scanned a card of belfry.brussels.", Cards.unknownText("belfry.brussels"))
        assertEquals("Welcome to Sourdough starters!", Cards.welcomeTitle(true, "Sourdough starters"))
        assertEquals("Welcome to # tool-library!", Cards.welcomeTitle(false, "tool-library"))
        assertEquals("You started it. Say hi and share something to get it going: a photo, a tip, a question.", Cards.welcomeText(true, CardWelcome.STARTED))
        assertEquals("Say hi and share something: a photo, a tip, a question.", Cards.welcomeText(true, CardWelcome.JOINED))
        assertEquals("You started it. Share something to get it going: a link, a photo, a question.", Cards.welcomeText(false, CardWelcome.STARTED))
        assertEquals("No introductions needed: share what you know.", Cards.welcomeText(false, CardWelcome.JOINED))
    }

    @Test fun theCardsLineInAGroup() {
        val key = "ab".repeat(32)
        assertTrue(Cards.isCardLine(key, listOf(listOf("h", "pod-x"), listOf("card", "1")), key))
        assertFalse(Cards.isCardLine("cd".repeat(32), listOf(listOf("card", "1")), key), "only the groups key writes it")
        assertFalse(Cards.isCardLine(key, listOf(listOf("card", "2")), key))
        assertFalse(Cards.isCardLine(key, listOf(listOf("card", "1")), null))
        assertEquals("You started this group from a printed card", Cards.cardLine("You", pod = true))
        assertEquals("Dev Patel started this topic from a printed card", Cards.cardLine("Dev Patel", pod = false))
        assertEquals("This topic was started from a printed card", Cards.cardLine(null, pod = false))
    }

    @Test fun aGuestWhoCameByACardKeepsItsKind() {
        assertEquals("topic", cardOf(obj("""{"card":"topic"}""")))
        assertEquals("group", cardOf(obj("""{"card":"group"}""")))
        assertNull(cardOf(obj("""{"card":"other"}""")))
        assertNull(cardOf(obj("""{"card":true}""")))
        assertNull(cardOf(obj("{}")))
        assertNull(cardOf(null))
        val saved = SavedSession(Community.defaults("example.org"), "bunker://${"ab".repeat(32)}?relay=wss%3A%2F%2Fr.example.org", "cd".repeat(32), "ef".repeat(32), GuestInfo("tool-library", saved = false, reading = true, card = "topic"))
        val back = SavedSession.fromJsonString(saved.toJsonString())!!
        assertEquals("topic", back.guest!!.card)
        assertTrue(back.guest!!.reading)
        // every other guest has none
        assertNull(SavedSession.fromJsonString(saved.copy(guest = GuestInfo("general", false)).toJsonString())!!.guest!!.card)
    }

    @Test fun theDemosCards() {
        assertEquals(CardInfo.Unclaimed(CardKind.GROUP), DemoCards.info(DemoCards.FREE_GROUP))
        assertEquals(CardInfo.Unclaimed(CardKind.TOPIC), DemoCards.info(DemoCards.FREE_TOPIC))
        assertIs<CardInfo.Claimed>(DemoCards.info(DemoCards.CLAIMED))
        assertEquals(CardKind.TOPIC, (DemoCards.info(DemoCards.CLAIMED_TOPIC) as CardInfo.Claimed).kind)
        assertTrue(Cards.isInvite((DemoCards.info(DemoCards.CLAIMED) as CardInfo.Claimed).invite))
        assertEquals(CardInfo.Retired(CardKind.GROUP, "Sourdough starters"), DemoCards.info(DemoCards.RETIRED))
        assertEquals(CardInfo.Archived(CardKind.GROUP, "Book club"), DemoCards.info(DemoCards.ARCHIVED))
        assertEquals(CardInfo.Unknown, DemoCards.info("ZZZZZZ"))
        for (id in listOf(DemoCards.FREE_GROUP, DemoCards.FREE_TOPIC, DemoCards.CLAIMED, DemoCards.CLAIMED_TOPIC, DemoCards.RETIRED, DemoCards.ARCHIVED)) assertTrue(Cards.isId(id), id)
        // a card this demo started is claimed from then on
        assertEquals(CardInfo.Claimed(CardKind.GROUP, "pod-x-1", "pod-x-1", DemoCards.invite(DemoCards.FREE_GROUP)), DemoCards.info(DemoCards.FREE_GROUP, mapOf(DemoCards.FREE_GROUP to "pod-x-1")))
        assertEquals(HashtagState.TAKEN, DemoCards.hashtag("general"))
        assertEquals(HashtagState.FREE, DemoCards.hashtag("tool-library"))
        assertEquals(HashtagState.INVALID, DemoCards.hashtag("a"))
    }
}
