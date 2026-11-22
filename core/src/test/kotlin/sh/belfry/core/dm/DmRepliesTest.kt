package sh.belfry.core.dm

import sh.belfry.core.model.OneShape
import sh.belfry.core.read.ReadState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Replies in a direct message (docs/one-shape.md 3): shown under their root; an unknown root shows in line. */
class DmRepliesTest {
    private val me = "a".repeat(64)
    private val maya = "b".repeat(64)
    private val conv = "n:$maya"

    private fun dm(rid: String, at: Long, root: String? = null, from: String = maya) =
        Dm(rid, conv, from, from == me, at, 14, "text $rid", root = root)

    @Test fun rootFoundGoesUnder() {
        val list = listOf(dm("r", 1), dm("x", 2, root = "r"), dm("o", 3), dm("y", 4, root = "r", from = me))
        val s = DmReplies.split(list)
        assertEquals(listOf("r", "o"), s.inline.map { it.rid })
        assertEquals(listOf("x", "y"), s.replies["r"]!!.map { it.rid })
    }

    @Test fun unknownRootShowsInLine() {
        val list = listOf(dm("o", 1), dm("x", 2, root = "gone"))
        val s = DmReplies.split(list)
        assertEquals(listOf("o", "x"), s.inline.map { it.rid })
        assertTrue(s.replies.isEmpty())
    }

    @Test fun replyToAReplyJoinsTheTopThread() {
        val list = listOf(dm("r", 1), dm("x", 2, root = "r"), dm("z", 3, root = "x"))
        val s = DmReplies.split(list)
        assertEquals(listOf("r"), s.inline.map { it.rid })
        assertEquals(listOf("x", "z"), s.replies["r"]!!.map { it.rid })
    }

    @Test fun aLoopShowsInLine() {
        val list = listOf(dm("x", 1, root = "y"), dm("y", 2, root = "x"))
        assertEquals(listOf("x", "y"), DmReplies.split(list).inline.map { it.rid })
    }

    /** The snapshot: in line, replies, and a reply still counts as unread for its conversation. */
    @Test fun snapshotCountsReplies() {
        val list = listOf(dm("r", 10), dm("x", 20, root = "r"))
        val snap = DmSnapshot(list, emptyMap(), ReadState.EMPTY, me, 100)
        assertEquals(listOf("r"), snap.inline(conv).map { it.rid })
        assertEquals(listOf("x"), snap.replies(conv, "r").map { it.rid })
        assertEquals(2, snap.threads.single().unread)
        assertEquals("x", snap.threads.single().last.rid)
    }

    @Test fun readsTheRootTag() {
        val rumor = GiftWrap.rumor(14, maya, listOf(listOf("p", me), OneShape.dmReplyTag("r00t")), "the reel", 5)
        val d = DmReader.fromRumor(rumor, me, null, "https://files.example", emptyList())!!
        assertEquals("r00t", d.root)
        assertEquals(d, Dm.fromJson(d.toJson()))
        val plain = DmReader.fromRumor(GiftWrap.rumor(14, maya, listOf(listOf("p", me)), "hi", 5), me, null, "https://files.example", emptyList())!!
        assertNull(plain.root)
    }
}
