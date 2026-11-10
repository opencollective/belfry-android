package sh.belfry.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import sh.belfry.core.Vectors
import sh.belfry.core.crypto.Nip19
import sh.belfry.core.markdown.Inline
import sh.belfry.core.markdown.Markdown
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.int
import sh.belfry.core.nostr.obj
import sh.belfry.core.nostr.s
import sh.belfry.core.nostr.str
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `#channel` references (vectors/channel-refs.json, made with the web chat's nostr-tools). */
class ChannelRefsTest {
    private fun JsonObject.list(key: String): List<JsonObject> = (this[key] as? JsonArray)?.map { it as JsonObject } ?: emptyList()
    private val v = Vectors.load("channel-refs.json")

    @Test
    fun naddrEncodesLikeTheWeb() {
        val cases = v.list("encode")
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val relays = (c["relays"] as JsonArray).map { it.str()!! }
            val a = Nip19.Addr(c["kind"].int()!!, c.s("pubkey")!!, c.s("identifier")!!, relays)
            assertEquals(c.s("naddr"), Nip19.naddr(a), c.s("identifier"))
            assertEquals(a, Nip19.decodeNaddr(c.s("naddr")!!))
        }
    }

    @Test
    fun naddrDecodesToItsGroup() {
        for (c in v.list("decode")) {
            val want = c["group"]?.takeIf { it !is JsonNull }?.str()
            assertEquals(want, ChannelRefs.groupOf(c.s("naddr")!!), c.s("naddr"))
        }
    }

    @Test
    fun previewsNameTheChannel() {
        val names = v["groups"].obj()!!.mapValues { it.value.str()!! }
        for (c in v.list("plain")) assertEquals(c.s("plain"), ChannelRefs.plain(c.s("text")!!) { names[it] }, c.s("text"))
    }

    // ---- a world with two channels ----

    private val relayKey = "959c24202b2b46602cf8614c4d930f571518551fbf121930b371a2ca321beddd"
    private val me = "aa".repeat(32)
    private fun meta(id: String, name: String, at: Long) = Event("m-$id", relayKey, at, 39000, listOf(listOf("d", id), listOf("name", name)), "")
    private fun msg(id: String, h: String, at: Long, text: String) = Event(id, me, at, 9, listOf(listOf("h", h)), text)
    private fun world(vararg extra: Event) = World(
        listOf(meta("general", "General", 1), meta("pod-3f9a1c", "Garden pod", 2), meta("ev-1", "Dinner", 3), msg("x1", "pod-3f9a1c", 50, "hi")) + extra,
        emptyList(), WorldContext(me = me, groupsRelay = "wss://groups-relay.example.org", zone = ZoneOffset.UTC),
    )

    @Test
    fun aChannelsReferenceIsItsNaddr() {
        val w = world()
        val general = v.list("encode").first { it.s("identifier") == "general" && (it["relays"] as JsonArray).size == 1 }
        assertEquals("nostr:" + general.s("naddr"), w.channelRef("general"))
        assertNull(w.channelRef("nowhere"))
        assertEquals("General", w.channelOfRef(general.s("naddr")!!)?.name)
    }

    @Test
    fun thePickerOffersChannelsNotEvents() {
        val w = world()
        assertEquals(listOf("pod-3f9a1c", "general"), w.channelCandidates("").map { it.group.id })
        assertEquals(listOf("pod-3f9a1c"), w.channelCandidates("gar").map { it.group.id })
        assertEquals(listOf("pod-3f9a1c"), w.channelCandidates("pod").map { it.group.id })
        assertEquals(emptyList(), w.channelCandidates("din"))
        assertEquals("ge", ChannelRefs.query("see #ge"))
        assertEquals("", ChannelRefs.query("#"))
        assertNull(ChannelRefs.query("a&#38"))
        assertNull(ChannelRefs.query("see #ge "))
        val (text, caret) = ChannelRefs.pick("see #ge", 7, "ge", "General")
        assertEquals("see #General ", text)
        assertEquals(13, caret)
    }

    @Test
    fun aPickIsSentAsTheReferenceAndEditedAsTheName() {
        val w = world()
        val ref = w.channelRef("pod-3f9a1c")!!
        val sent = ChannelRefs.substitute("move to #Garden pod please, not #Garden", listOf("#Garden pod" to ref))
        assertEquals("move to $ref please, not #Garden", sent)
        // a hand-typed #word stays a topic; the reference is not one
        assertEquals(listOf("garden"), Topics.hashtags(sent))
        val (back, picks) = w.unsubstituteChannels(sent)
        assertEquals("move to #Garden pod please, not #Garden", back)
        assertEquals(listOf("#Garden pod" to ref), picks)
    }

    @Test
    fun chatTextShowsAReferenceAndPreviewsNameIt() {
        val w = world()
        val ref = w.channelRef("general")!!
        val nodes = Markdown.merged(Markdown.inline("see $ref, #garden", Markdown.rich))
        assertEquals(listOf(Inline.Text("see "), Inline.ChannelRef(ref.removePrefix("nostr:")), Inline.Text(", "), Inline.Topic("garden")), nodes)
        assertEquals("see #General, #garden", Markdown.plain("see $ref, #garden", channel = { w.refLabel(it) }))
        assertEquals("see #General", w.plainRefs("see $ref"))
        val w2 = world(msg("x2", "general", 60, "Notes in $ref"))
        val m = w2.messages("general").first { it.id == "x2" }
        assertEquals("Notes in #General", w2.deletePreview(m))
    }
}
