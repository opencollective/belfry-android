package sh.belfry.core.engine

import sh.belfry.core.net.GroupCard
import sh.belfry.core.net.GroupCardApi

/**
 * The printed card a group or topic was started from, for its stewards (SPEC 19.76; docs/cards.md
 * §2.5, §2.6): [forGroup] asks the belfry (nothing is kept: the count moves), [retire] retires it.
 * Retiring is not queued: it is asked for with a confirmation and answers at once, like a
 * channel's address. The demo answers here.
 */
class GroupCardService internal constructor(private val s: Session) {
    private val demo: Boolean get() = s.localOnly
    private val demoRetired: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** GROUP's card, or null: none in use, not mine to see (403), an older belfry, or no answer just now. */
    suspend fun forGroup(group: String): GroupCard? {
        if (demo) return demoCard(group)
        return try {
            s.apiCall(write = false) { GroupCardApi(it, s.signer).forGroup(group) }
        } catch (e: ActionException) {
            null
        }
    }

    /** Retires CARD. Throws [ActionException] with fixed words ([sh.belfry.core.net.GroupCards.ERRORS]). */
    suspend fun retire(card: GroupCard) {
        if (demo) { s.checkWrite(); demoRetired.add(card.id); return }
        s.apiCall(write = true) { GroupCardApi(it, s.signer).retire(card.id) }
    }

    // the demo: the two groups Demo seeds from cards, for their stewards and the belfry's admins
    private fun demoCard(group: String): GroupCard? {
        val w = s.world.value
        if (!w.canAdmin(group)) return null
        val card = when (group) {
            Demo.POD_CARD -> GroupCard(Demo.CARD_GROUP, false, "Sam Carter", Demo.sam.pubkey, 8)
            Demo.TOPIC_CARD -> GroupCard(Demo.CARD_TOPIC, true, "Lena Fischer", Demo.lena.pubkey, 3)
            else -> return null
        }
        return card.takeIf { it.id !in demoRetired }
    }
}
