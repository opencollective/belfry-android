package sh.belfry.core.engine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import sh.belfry.core.model.Actions
import sh.belfry.core.model.CardError
import sh.belfry.core.model.CardFailed
import sh.belfry.core.model.CardForm
import sh.belfry.core.model.CardInfo
import sh.belfry.core.model.CardKind
import sh.belfry.core.model.Cards
import sh.belfry.core.model.Draft
import sh.belfry.core.model.HashtagState
import sh.belfry.core.net.CardsApi
import sh.belfry.core.outbox.Target
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.s

/**
 * The demo's printed cards (the web's, id for id): K7Q2MX (a group card) and T9PC4H (a topic
 * card) are free; CXAWMS is claimed (TPCHAT: a claimed topic card), RETRDX retired, ARCH9V
 * archived; anything else is unknown. The code is 123456. Nothing leaves the device.
 */
object DemoCards {
    const val FREE_GROUP = "K7Q2MX"
    const val FREE_TOPIC = "T9PC4H"
    const val CLAIMED = "CXAWMS"
    const val CLAIMED_TOPIC = "TPCHAT"
    const val RETIRED = "RETRDX"
    const val ARCHIVED = "ARCH9V"
    const val CODE = "123456"
    /** The names a new topic can't take in the demo (the web's list). */
    val TAKEN = setOf("general", "events", "market", "requests")

    fun invite(id: String): String = "demo-card-invite-" + id.lowercase() + "-xxxxxxxx"

    /** CLAIMED: the cards this demo started (card id → group id). */
    fun info(id: String, claimed: Map<String, String> = emptyMap()): CardInfo {
        val kind = if (id == FREE_TOPIC || id == CLAIMED_TOPIC) CardKind.TOPIC else CardKind.GROUP
        claimed[id]?.let { return CardInfo.Claimed(kind, it, it, invite(id)) }
        return when (id) {
            FREE_GROUP, FREE_TOPIC -> CardInfo.Unclaimed(kind)
            CLAIMED, CLAIMED_TOPIC -> CardInfo.Claimed(kind, Demo.GENERAL, "Townsquare", invite(id))
            RETIRED -> CardInfo.Retired(kind, "Sourdough starters")
            ARCHIVED -> CardInfo.Archived(kind, "Book club")
            else -> CardInfo.Unknown
        }
    }

    fun hashtag(name: String, more: Set<String> = emptySet()): HashtagState {
        val h = Cards.hashtagSlug(name)
        return if (h.length < 2) HashtagState.INVALID else if (h in TAKEN || h in more) HashtagState.TAKEN else HashtagState.FREE
    }

    /** What a visitor's `claim-start` answers in the demo: nothing is mailed. */
    fun start(id: String) {
        when (val s = info(id)) {
            is CardInfo.Claimed -> throw CardFailed(CardError.CLAIMED, group = s.group, invite = s.invite)
            is CardInfo.Unclaimed -> Unit
            is CardInfo.Retired -> throw CardFailed(CardError.RETIRED)
            else -> throw CardFailed(CardError.UNKNOWN)
        }
    }
}

/**
 * Printed cards for one signed-in [Session] (SPEC 19.72, 19.73): what a card is, a topic's
 * hashtag, and the claim by this account. The claim is one HTTP call that needs its answer (the
 * group's id), so it is not queued; a guest in guest mode sends nothing and is told to save the
 * account first. The demo answers by itself ([DemoCards]) and makes the group or topic as the
 * belfry's groups key would.
 */
class CardsService internal constructor(private val s: Session) {
    private fun demoClaimed(): Map<String, String> {
        val o = parseJsonOrNull(s.store.get(K_DEMO) ?: return emptyMap()) as? JsonObject ?: return emptyMap()
        return o.keys.mapNotNull { k -> o.s(k)?.let { k to it } }.toMap()
    }

    suspend fun info(id: String): CardInfo {
        if (!Cards.isId(id)) return CardInfo.Unknown
        if (s.localOnly) return DemoCards.info(id, demoClaimed())
        return s.apiCall(write = false) { CardsApi(it).info(id) }
    }

    suspend fun hashtag(name: String): HashtagState {
        if (s.localOnly) return DemoCards.hashtag(name, s.world.value.groups.map { it.id }.toSet())
        return try { s.apiCall(write = false) { CardsApi(it).hashtag(name) } } catch (e: ActionException) { HashtagState.UNKNOWN }
    }

    /** Starts the group or topic of card ID as this account; its id. Throws [CardFailed]. */
    suspend fun claim(id: String, kind: CardKind, f: CardForm): String {
        // guest mode writes nothing (SPEC 19.26): the belfry would say the same
        if (s.reading) throw CardFailed(CardError.GUEST_NOT_SAVED)
        if (s.localOnly) return demoClaim(id, kind, f)
        val group = try {
            s.apiCall(write = false) { CardsApi(it).claim(s.signer, id, kind, f) }
        } catch (e: ActionException) {
            throw CardFailed(if (e.code == "network") CardError.OFFLINE else CardError.FAILED)
        }
        s.groupsAgain()
        return group
    }

    private suspend fun demoClaim(id: String, kind: CardKind, f: CardForm): String {
        when (val i = info(id)) {
            is CardInfo.Claimed -> throw CardFailed(CardError.CLAIMED, group = i.group, invite = i.invite)
            is CardInfo.Unclaimed -> Unit
            is CardInfo.Retired -> throw CardFailed(CardError.RETIRED)
            else -> throw CardFailed(CardError.UNKNOWN)
        }
        val title = Cards.plainLabel(f.title).take(80)
        if (title.isEmpty() && kind != CardKind.TOPIC) throw CardFailed(CardError.INVALID, field = "title")
        val group = if (kind == CardKind.TOPIC) {
            val tag = Cards.hashtagSlug(f.hashtag)
            if (hashtag(tag) != HashtagState.FREE) throw CardFailed(CardError.HASHTAG_TAKEN)
            // as the cards server makes a topic now (SPEC 19.89): a channel whose id and name are the hashtag, the description its about
            s.send(Actions.createChannel(tag, tag, Cards.topicAbout(f)))
            tag
        } else {
            s.createPod(title, f.about.trim().take(500), Demo.GENERAL)
        }
        // the first line in it, as the groups key writes it (docs/cards.md §2.2 step 4): never the card's id
        val name = s.world.value.realName(s.me)
        s.send(Draft(Target.GROUPS, 9, "$name started this ${kind.word} from a printed card", listOf(listOf("h", group), listOf("p", s.me), listOf("card", "1"))))
        s.store.set(K_DEMO, buildJsonObject { for ((k, v) in demoClaimed() + (id to group)) put(k, JsonPrimitive(v)) }.toString())
        return group
    }

    companion object {
        /** The demo's claimed cards, in the demo account's own store. */
        const val K_DEMO = "demo-cards"
    }
}

/** Printed cards (SPEC 19.72, 19.73). */
val Session.cards: CardsService get() = CardsService(this)
