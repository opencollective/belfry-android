package sh.belfry.core.typing

import kotlinx.coroutines.runBlocking
import sh.belfry.core.dm.GiftWrap
import sh.belfry.core.nostr.Event
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.nostr.isValid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/typing.md @7750419 (collective-belfry). vectors/typing.json (belfry-ios) to come. */
class TypingTest {
    private val t0 = 1_793_000_000L
    private val ch = TypingPlace.channel("garden")
    private val th = TypingPlace.thread("garden", "root1")

    @Test fun groupEvents() {
        val a = Typing.groupTemplate(ch, now = t0)
        assertEquals(20100, a.kind); assertEquals("", a.content); assertEquals(listOf(listOf("h", "garden")), a.tags); assertEquals(t0, a.createdAt)
        val b = Typing.groupTemplate(th, stop = true, now = t0)
        assertEquals(listOf(listOf("h", "garden"), listOf("e", "root1"), listOf("status", "stop")), b.tags)
        val ev = Event("x", "pk", t0, 20100, b.tags, "", "")
        assertEquals(th, Typing.placeOfGroupEvent(ev))
        assertTrue(Typing.isStop(ev.tags))
        assertEquals(ch, Typing.placeOfGroupEvent(ev.copy(tags = listOf(listOf("h", "garden")))))
        assertNull(Typing.placeOfGroupEvent(ev.copy(tags = listOf(listOf("e", "root1")))))
        assertNull(Typing.placeOfGroupEvent(ev.copy(kind = 9)))
    }

    @Test fun dmRumorsAndWraps() = runBlocking {
        val me = LocalSigner.random(); val lina = LocalSigner.random(); val tom = LocalSigner.random()
        val conv = sh.belfry.core.mail.MailThreads.nostrConv(me.pubkey, listOf(lina.pubkey, tom.pubkey), me.pubkey)
        val to = Typing.recipients(conv, me.pubkey)
        assertEquals(setOf(lina.pubkey, tom.pubkey), to.toSet())
        assertEquals(emptyList(), Typing.recipients("m:someone@example.org", me.pubkey))
        assertEquals(emptyList(), Typing.recipients("n:${me.pubkey}", me.pubkey)) // notes to myself
        val rumor = Typing.dmRumor(me.pubkey, to, now = t0)
        assertEquals("", rumor.sig); assertEquals(20100, rumor.kind)
        val wrap = GiftWrap.wrapEphemeral(rumor, lina.pubkey, me, now = t0)
        assertEquals(21059, wrap.kind); assertEquals(t0, wrap.createdAt); assertTrue(wrap.isValid())
        assertEquals(listOf(listOf("p", lina.pubkey)), wrap.tags)
        val opened = GiftWrap.unwrap(wrap, lina)!!
        assertEquals(me.pubkey, opened.rumor.pubkey)
        // Lina sees the same conversation as mine, from her side
        val hers = Typing.placeOfRumor(opened.rumor, lina.pubkey)!!
        assertEquals(sh.belfry.core.mail.MailThreads.nostrConv(lina.pubkey, listOf(me.pubkey, tom.pubkey), lina.pubkey), hers.conv)
        assertNull(Typing.placeOfRumor(opened.rumor.copy(kind = 14), lina.pubkey))
    }

    @Test fun sendingAtMostEveryFourSeconds() {
        val s = TypingSender()
        assertTrue(s.onText(ch, "H", (t0).toDouble()))
        assertFalse(s.onText(ch, "He", (t0 + 1).toDouble()))
        assertFalse(s.onText(ch, "Hel", (t0 + 3).toDouble()))
        assertTrue(s.onText(ch, "Hell", (t0 + 4).toDouble()))
        assertTrue(s.onText(th, "x", (t0 + 4).toDouble())) // another place has its own gap
        assertFalse(s.onText(TypingPlace.channel("other"), "   ", t0.toDouble())) // only spaces
        assertFalse(s.onText(TypingPlace.channel("other"), "draft", t0.toDouble(), restored = true))
        assertFalse(s.onText(TypingPlace.channel("other"), "x", t0.toDouble(), enabled = false))
        // cleared within 6 s of the last one: a stop; later or never sent: nothing
        assertTrue(s.onCleared(ch, (t0 + 9).toDouble()))
        assertFalse(s.onCleared(ch, (t0 + 9).toDouble()))
        assertFalse(s.onCleared(th, (t0 + 10).toDouble()))
        // sent: no stop, and the next keystroke may go at once
        assertTrue(s.onText(ch, "a", (t0 + 20).toDouble())); s.onSent(ch)
        assertFalse(s.onCleared(ch, (t0 + 21).toDouble()))
        assertTrue(s.onText(ch, "b", (t0 + 21).toDouble()))
    }

    @Test fun showingForSixSeconds() {
        val b = TypingBoard()
        assertTrue(b.apply(ch, "lina", t0, stop = false, now = (t0).toDouble()))
        assertTrue(b.apply(ch, "tom", t0 + 1, stop = false, now = (t0 + 1).toDouble()))
        assertEquals(listOf("lina", "tom"), b.typers(ch, (t0 + 2).toDouble()))
        assertEquals(emptyList(), b.typers(th, (t0 + 2).toDouble())) // the thread is another place
        assertEquals(listOf("tom"), b.typers(ch, (t0 + 6).toDouble()))
        assertEquals((t0 + 6).toDouble(), b.nextChange((t0 + 2).toDouble()))
        // a message of hers made at or after her typing event ends it
        b.apply(ch, "lina", t0 + 6, stop = false, now = (t0 + 6).toDouble())
        assertEquals(listOf("tom", "lina"), b.typers(ch, (t0 + 6).toDouble()))
        assertEquals(listOf("tom"), b.typers(ch, (t0 + 6).toDouble()) { if (it == "lina") t0 + 6 else 0 })
        // stop
        b.apply(ch, "tom", t0 + 7, stop = true, now = (t0 + 7).toDouble())
        assertEquals(listOf("lina"), b.typers(ch, (t0 + 7).toDouble()))
        // too old / too far ahead (relays replay ephemeral events)
        assertFalse(b.apply(ch, "old", t0 - 11, stop = false, now = (t0).toDouble()))
        assertFalse(b.apply(ch, "ahead", t0 + 11, stop = false, now = (t0).toDouble()))
        assertTrue(b.apply(ch, "edge", t0 - 10, stop = false, now = (t0).toDouble()))
        // an older event arriving after a newer one changes nothing
        b.apply(th, "lina", t0 + 5, stop = false, now = (t0 + 5).toDouble())
        assertFalse(b.apply(th, "lina", t0 + 4, stop = true, now = (t0 + 5).toDouble()))
        assertEquals(listOf("lina"), b.typers(th, (t0 + 5).toDouble()))
        assertEquals(setOf(ch, th), b.places((t0 + 7).toDouble()))
        val st = b.snapshot((t0 + 7).toDouble())
        assertEquals(listOf("lina"), st.who(th))
        assertEquals(emptyList(), st.who(th) { t0 + 5 })
        b.apply(TypingPlace.dm("n:a,b"), "a", t0 + 7, stop = false, now = (t0 + 7).toDouble())
        assertEquals(setOf("n:a,b"), b.snapshot((t0 + 7).toDouble()).dmConvs)
        b.prune((t0 + 100).toDouble())
        assertEquals(emptySet(), b.places((t0 + 100).toDouble()))
        assertNull(b.nextChange((t0 + 100).toDouble()))
    }

    @Test fun words() {
        assertNull(Typing.line(emptyList()))
        assertEquals("Lina is typing…", Typing.line(listOf("Lina")))
        assertEquals("Lina and Tom are typing…", Typing.line(listOf("Lina", "Tom")))
        assertEquals("Several people are typing…", Typing.line(listOf("Lina", "Tom", "Ana")))
    }
}
