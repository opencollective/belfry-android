package sh.belfry.core.enc

import sh.belfry.core.crypto.Hex
import sh.belfry.core.crypto.Keys
import sh.belfry.core.crypto.Nip44
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.EventTemplate

/** §6 + §17.14 + §18.2 + §20 + §21: encrypted messages (kind 8572), people and group. */
object EncMessages {
    private fun sealFor(device: ByteArray, r: Event, K: String, f: Fixed?, i: Int): Event {
        val json = EncJson.rumorJson(r)
        if (Enc.utf8(json) > Enc.MAX_RUMOR) throw EncException("the message is too long to encrypt")
        return Enc.signWith(device, Enc.SEAL, f?.sealAt ?: f?.at ?: Enc.now(), emptyList(), Nip44.encrypt(json, Enc.conv(device, K), f.nonceOf(i)), f?.aux)
    }

    /** §6 people: one 8572 per person (ME's own copy silent), each under its own one-time key, `["d", alias of K for that account]`. */
    fun peopleMessage(device: ByteArray, rumor: Event, K: String, people: List<String>, me: String, f: Fixed? = null): List<Event> {
        val seal = EncJson.signedJson(sealFor(device, Enc.withId(rumor), K, f, 0))
        return people.mapIndexed { i, a ->
            val once = f?.oneTime ?: Keys.generateSecret()
            val tags = listOf(listOf("p", a), listOf("d", EncKeys.alias(K, a))) + (if (a == me) listOf(Enc.SILENT) else emptyList())
            Enc.signWith(once, Enc.MSG, f?.at ?: Enc.now(), tags, Nip44.encrypt(seal, Enc.conv(once, K), f.nonceOf(100 + i)), f?.aux)
        }
    }

    /** §6 group: one 8572 by the sender's account (unsigned: the signer signs it), tags h, d = K, and p per mention (§17.14). */
    fun groupMessage(device: ByteArray, rumor: Event, K: String, group: String, f: Fixed? = null, mentions: List<String> = emptyList()): EventTemplate {
        val seal = sealFor(device, Enc.withId(rumor), K, f, 0)
        val tags = listOf(listOf("h", group), listOf("d", K)) + mentions.distinct().filter { Enc.isKey(it) }.map { listOf("p", it) }
        return EventTemplate(Enc.MSG, EncJson.signedJson(seal), tags, f?.at ?: Enc.now())
    }

    /** The key a message names: its d (people: an alias, group: K). */
    fun named(e: Event): String? = Enc.one(e, "d")

    private fun openSeal(k: String, sealJson: String): Opened {
        val seal = EncJson.event(sealJson) ?: throw EncException("the seal is not an event")
        if (!Enc.valid(seal) || seal.kind != Enc.SEAL) throw EncException("the seal is not signed")
        val rumor = (try { EncJson.rumor(EncJson.parse(Nip44.decrypt(seal.content, Enc.conv(Hex.decode(k), seal.pubkey)))) } catch (e: Exception) { null })
            ?: throw EncException("the seal does not open with this key")
        return Opened(seal, rumor)
    }

    /** (seal, rumor) of a people 8572 with the conversation secret K_SECRET. Throws [EncException] when it doesn't open. */
    fun openPeopleMessage(kSecret: String, msg: Event): Opened {
        if (!Enc.valid(msg) || msg.kind != Enc.MSG) throw EncException("not a message")
        val sealJson = try { Nip44.decrypt(msg.content, Enc.conv(Hex.decode(kSecret), msg.pubkey)) } catch (e: Exception) { throw EncException("the message does not open with this key") }
        return openSeal(kSecret, sealJson)
    }

    /** (seal, rumor) of a group 8572 with the secret K_SECRET. */
    fun openGroupMessage(kSecret: String, msg: Event): Opened {
        if (!Enc.valid(msg) || msg.kind != Enc.MSG) throw EncException("not a message")
        return openSeal(kSecret, msg.content)
    }

    /** §6 + §18.2 people: the seal's signer signs for the author at the OUTER date, the rumor is not newer than outer + 5 min, its people are the key's. */
    fun acceptPeopleMessage(outer: Event, seal: Event, rumor: Event, keyPeople: String, chain: Devices): Verdict {
        if (EncKeys.peopleOf(rumor) != keyPeople) return Verdict.NO
        return signer(outer, seal, rumor, chain)
    }

    /** §6 group: the outer event is the rumor's author, the rumor's and the outer's h are the key's group, the signer signs for the author. */
    fun acceptGroupMessage(outer: Event, seal: Event, rumor: Event, group: String, chain: Devices): Verdict {
        if (outer.pubkey != rumor.pubkey || Enc.one(rumor, "h") != group || Enc.one(outer, "h") != group) return Verdict.NO
        return signer(outer, seal, rumor, chain)
    }

    private fun signer(outer: Event, seal: Event, rumor: Event, chain: Devices): Verdict {
        if (rumor.createdAt > outer.createdAt + Enc.SKEW) return Verdict.NO
        return when (chain) {
            Devices.Service -> if (seal.pubkey == rumor.pubkey) Verdict.OK else Verdict.NO
            Devices.Unknown -> Verdict.WAIT // no list: kept and judged again, never the account key (§20)
            is Devices.Listed -> {
                val c = chain.chain
                // the account's own key is one of its devices only when a list says so (self)
                if (seal.pubkey == rumor.pubkey && c.lists.none { seal.pubkey in it.devices }) return Verdict.NO
                when (EncLists.signsAt(c, seal.pubkey, outer.createdAt)) { null -> Verdict.WAIT; true -> Verdict.OK; false -> Verdict.NO }
            }
        }
    }
}
