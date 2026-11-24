package sh.belfry.core.enc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import sh.belfry.core.crypto.Hex
import sh.belfry.core.crypto.Keys
import sh.belfry.core.crypto.Nip44
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.Tag

/** A key in a pack (§7.5, §16.3, §19): a people key rumor as received, a group key, or a version-1 letterbox secret. */
sealed interface PackKey {
    data class People(val rumor: Event) : PackKey
    /** A group key received in a pack opens messages; it is never adopted for sending (no authenticated entry set). */
    data class Group(val secret: String, val pub: String, val group: String) : PackKey
    data class V1(val secret: String, val lb: String) : PackKey
}

/** An opened pack: the sharing device and the keys that checked out. */
data class PackOpened(val from: String, val keys: List<PackKey>)

/** What an answer (8575) answers: an ask (its event id) or the card of a device that never asked. */
sealed interface AnswerTo {
    data class Ask(val id: String) : AnswerTo
    data class Device(val device: String) : AnswerTo
}

enum class AnswerWord(val wire: String) { SHARED("shared"), REMOVED("removed") }

/** An ask seen from a device on the account's current list. */
data class AskSeen(val device: String, val id: String, val at: Long)

/** An answer that counts (§17.6, §20): BY a device on the current list, listed before the device it is about. */
data class AnswerSeen(val by: String, val ask: String?, val device: String?, val answer: AnswerWord, val at: Long)

/** §7, §16.3, §17.6, §18.3, §20: key packs (8573), asks (8574) and answers (8575). */
object EncPacks {
    private fun packRumor(sharer: String, account: String, p: PackKey, at: Long): JsonObject {
        fun obj(pubkey: String, createdAt: Long, tags: List<Tag>, content: String) = buildJsonObject {
            put("kind", JsonPrimitive(Enc.KEY)); put("pubkey", JsonPrimitive(pubkey)); put("created_at", JsonPrimitive(createdAt))
            put("tags", EncJson.tags(tags)); put("content", JsonPrimitive(content))
        }
        return when (p) {
            is PackKey.People -> obj(p.rumor.pubkey, p.rumor.createdAt, p.rumor.tags, p.rumor.content)
            is PackKey.Group -> obj(sharer, at, listOf(listOf("K", p.pub), listOf("h", p.group)), p.secret)
            is PackKey.V1 -> obj(sharer, at, listOf(listOf("v1", p.lb), listOf("p", account)), p.secret)
        }
    }

    /** §7.5 packs to the new device TO: 8573 by a one-time key, `["p", account]`, `["d", to]`; a seal by SHARER holding a JSON list of key rumors, at most MAX_PACK bytes each. */
    fun packs(sharer: ByteArray, account: String, to: String, keys: List<PackKey>, f: Fixed? = null): List<Event> {
        val at = f?.at ?: Enc.now()
        val me = Enc.pub(sharer)
        val groups = mutableListOf(mutableListOf<JsonObject>())
        var size = 2 // bytes of the last group's JSON array
        for (k in keys) {
            val r = packRumor(me, account, k, at)
            val rb = Enc.utf8(EncJson.stringify(r))
            val last = groups.last()
            if (last.isNotEmpty() && size + 1 + rb > Enc.MAX_PACK) { groups += mutableListOf(r); size = 2 + rb }
            else { size += (if (last.isEmpty()) 0 else 1) + rb; last += r }
        }
        return groups.filterIndexed { i, g -> g.isNotEmpty() || i == 0 }.mapIndexed { i, g ->
            val seal = Enc.signWith(sharer, Enc.SEAL, f?.sealAt ?: at, emptyList(), Nip44.encrypt(EncJson.stringify(JsonArray(g)), Enc.conv(sharer, to), f.nonceOf(2 * i)), f?.aux)
            val once = f?.oneTime ?: Keys.generateSecret()
            Enc.signWith(once, Enc.PACK, at, listOf(listOf("p", account), listOf("d", to)), Nip44.encrypt(EncJson.signedJson(seal), Enc.conv(once, to), f.nonceOf(2 * i + 1)), f?.aux)
        }
    }

    /**
     * §16.3 + §17.6 + §18.3: the keys of a pack to this DEVICE of ACCOUNT, when its seal is signed by
     * another device on the account's current list, listed before this one, with no list missing
     * (CHAIN gaps). Keys that don't check out are left out; null when the pack doesn't.
     */
    fun openPack(device: ByteArray, account: String, pack: Event, chain: Chain?): PackOpened? {
        if (!Enc.valid(pack) || pack.kind != Enc.PACK) return null
        val me = Enc.pub(device)
        if (Enc.one(pack, "d") != me || Enc.one(pack, "p") != account) return null
        val seal = (try { EncJson.event(Nip44.decrypt(pack.content, Enc.conv(device, pack.pubkey))) } catch (e: Exception) { null }) ?: return null
        if (!Enc.valid(seal) || seal.kind != Enc.SEAL) return null
        val list = (try { EncJson.parse(Nip44.decrypt(seal.content, Enc.conv(device, seal.pubkey))) } catch (e: Exception) { null }) ?: return null
        if (chain == null || chain.gaps || seal.pubkey == me || seal.pubkey !in chain.current.devices ||
            !EncLists.listedBefore(chain, seal.pubkey, me) || list !is JsonArray) return null
        val keys = mutableListOf<PackKey>()
        for (x in list) {
            val r = EncJson.rumor(x) ?: continue
            if (r.kind != Enc.KEY || !Enc.isKey(r.content)) continue
            val pub = try { Enc.pub(Hex.decode(r.content)) } catch (e: Exception) { continue }
            val v1 = Enc.one(r, "v1")
            val h = Enc.one(r, "h")
            val K = Enc.one(r, "K")
            if (v1 != null) { if (v1 == pub && Enc.one(r, "p") == account) keys += PackKey.V1(r.content, v1); continue }
            if (K != pub) continue
            if (h != null) { if (h.isNotEmpty()) keys += PackKey.Group(r.content, pub, h); continue }
            if (account in Enc.values(r, "p")) keys += PackKey.People(r)
        }
        return PackOpened(seal.pubkey, keys)
    }

    /** §7.3 the ask, signed by the device. */
    fun ask(device: ByteArray, account: String, at: Long = Enc.now(), aux: ByteArray? = null): Event =
        Enc.signWith(device, Enc.ASK, at, listOf(listOf("p", account)), "", aux)

    /** §7.7 + §16.12 the answer, signed by the answering device: to an ask (e) or to the card of a device that never asked. */
    fun answer(device: ByteArray, account: String, to: AnswerTo, what: AnswerWord, at: Long = Enc.now(), aux: ByteArray? = null): Event {
        val about = when (to) { is AnswerTo.Ask -> listOf("e", to.id); is AnswerTo.Device -> listOf("device", to.device) }
        return Enc.signWith(device, Enc.ANSWER, at, listOf(listOf("p", account), about, listOf("answer", what.wire)), "", aux)
    }

    /** An ask of ACCOUNT signed by a device on its current list. */
    fun readAsk(e: Event, account: String, chain: Chain?): AskSeen? {
        if (!Enc.valid(e) || e.kind != Enc.ASK || Enc.one(e, "p") != account || chain == null || e.pubkey !in chain.current.devices) return null
        return AskSeen(e.pubkey, e.id, e.createdAt)
    }

    /**
     * An answer of ACCOUNT that counts: by a device on the current list (§20) listed before the device
     * it is about (§17.6), no list missing. ABOUT: the asking device of an e answer (the caller resolves it).
     */
    fun readAnswer(e: Event, account: String, chain: Chain?, about: String? = null): AnswerSeen? {
        if (!Enc.valid(e) || e.kind != Enc.ANSWER || Enc.one(e, "p") != account || chain == null || e.pubkey !in chain.current.devices) return null
        val dev = Enc.one(e, "device") ?: about
        if (dev.isNullOrEmpty() || chain.gaps || !EncLists.listedBefore(chain, e.pubkey, dev)) return null
        val word = AnswerWord.entries.firstOrNull { it.wire == Enc.one(e, "answer") } ?: return null
        val ask = Enc.one(e, "e")?.takeIf { it.isNotEmpty() }
        val device = Enc.one(e, "device")?.takeIf { it.isNotEmpty() }
        if ((ask == null) == (device == null) || (ask != null && !Enc.isKey(ask)) || (device != null && !Enc.isKey(device))) return null
        return AnswerSeen(e.pubkey, ask, device, word, e.createdAt)
    }
}
