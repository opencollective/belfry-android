package sh.belfry.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import sh.belfry.core.mail.KnownMail
import sh.belfry.core.mail.MailThreads
import sh.belfry.core.mail.Mime
import sh.belfry.core.markdown.Block
import sh.belfry.core.markdown.Inline
import sh.belfry.core.markdown.Markdown
import sh.belfry.core.nostr.arr
import sh.belfry.core.nostr.bool
import sh.belfry.core.nostr.int
import sh.belfry.core.nostr.long
import sh.belfry.core.nostr.obj
import sh.belfry.core.nostr.s
import sh.belfry.core.nostr.str
import sh.belfry.core.outbox.Outbox
import sh.belfry.core.outbox.OutboxItem
import sh.belfry.core.outbox.OutboxStatus
import sh.belfry.core.outbox.SendOutcome
import sh.belfry.core.outbox.Target
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextMailOutboxTest {
    private fun JsonObject.list(key: String): List<JsonObject> = (this[key] as? JsonArray)?.map { it as JsonObject } ?: emptyList()
    private fun JsonObject.strs(key: String): List<String> = (this[key] as? JsonArray)?.map { it.str()!! } ?: emptyList()

    // ---- markdown ----

    private fun inl(e: JsonElement): Inline {
        val n = e as JsonObject
        val kids = { (n["c"] as? JsonArray)?.map(::inl) ?: emptyList() }
        return when (n.s("t")) {
            "bold" -> Inline.Bold(kids())
            "italic" -> Inline.Italic(kids())
            "strike" -> Inline.Strike(kids())
            "code" -> Inline.Code(n.s("v") ?: "")
            "link" -> Inline.Link(n.s("href") ?: "", kids())
            "mention" -> Inline.Mention(n.s("npub") ?: "")
            "topic" -> Inline.Topic(n.s("name") ?: "")
            "text" -> Inline.Text(n.s("v") ?: "")
            else -> error("unknown inline " + n.s("t"))
        }
    }

    private fun lineList(e: JsonElement?): List<List<Inline>> = (e as? JsonArray)?.map { l -> (l as JsonArray).map(::inl) } ?: emptyList()

    private fun block(b: JsonObject): Block = when (b.s("type")) {
        "heading" -> Block.Heading(b["level"].int() ?: 1, b["c"].arr()?.map(::inl) ?: emptyList())
        "list" -> Block.ListBlock(false, lineList(b["items"]))
        "ordered_list" -> Block.ListBlock(true, lineList(b["items"]))
        "quote" -> Block.Quote(lineList(b["lines"]))
        "code" -> Block.CodeBlock(b.s("text") ?: "")
        "paragraph" -> Block.Paragraph(lineList(b["lines"]))
        else -> error("unknown block " + b.s("type"))
    }

    @Test
    fun markdownVectors() {
        val cases = Vectors.load("markdown.json").list("cases")
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val text = c.s("text")!!
            val got = Markdown.parse(text).map { Markdown.merged(it) }
            val want = c.list("blocks").map { Markdown.merged(block(it)) }
            assertEquals(want, got, text)
        }
    }

    @Test
    fun markdownExtras() {
        val npub = "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"
        // descriptions: no mentions or topics
        assertEquals(
            listOf<Block>(Block.Paragraph(listOf(listOf(Inline.Text("see #plan"))))),
            Markdown.parse("see #plan", Markdown.asIs),
        )
        // nsite links in chat text (newer than the vectors)
        assertEquals(
            listOf<Inline>(Inline.Text("look "), Inline.Nsite(npub, "blog", "nsite:$npub/blog"), Inline.Text(" ok")),
            Markdown.merged(Markdown.inline("look nsite:$npub/blog ok", Markdown.rich)),
        )
        // unsafe links stay text
        assertEquals(listOf<Inline>(Inline.Text("[x](javascript:alert(1))")), Markdown.merged(Markdown.inline("[x](javascript:alert(1))")))
        // previews
        assertEquals("Hello bold and @Ana code", Markdown.plain("Hello **bold** and nostr:$npub `code`") { "@Ana" })
        assertEquals("one two", Markdown.plain("one\ntwo"))
        assertEquals("a b #t", Markdown.text(Markdown.inline("**a** _b_ #t", Markdown.rich)))
    }

    @Test
    fun markdownHtml() {
        val html = Markdown.html("# Title\n\nHello **you** & <b>\n- one\n- [site](https://x.org/a)\n\n> quoted _it_\n\n```\n<pre>\n```\nsee https://belfry.sh.")
        val open = "<div style=\"font-family:-apple-system,Segoe UI,Roboto,sans-serif;font-size:15px;line-height:1.5\">"
        assertEquals(
            open + listOf(
                "<p><strong>Title</strong></p>",
                "<p>Hello <strong>you</strong> &amp; &lt;b&gt;</p>",
                "<ul><li>one</li><li><a href=\"https://x.org/a\">site</a></li></ul>",
                "<blockquote style=\"border-left:3px solid #d4d4d8;margin:0;padding-left:10px;color:#52525b\">quoted <em>it</em></blockquote>",
                "<pre style=\"background:#f4f4f5;padding:8px;border-radius:6px\">&lt;pre&gt;</pre>",
                "<p>see <a href=\"https://belfry.sh\">https://belfry.sh</a>.</p>",
            ).joinToString("\n") + "</div>",
            html,
        )
        assertFalse(Markdown.html("[x](javascript:alert(1)) <script>").contains("<script>"))
        assertFalse(Markdown.html("[x](javascript:alert(1))").contains("<a "))
    }

    // ---- MIME ----

    @Test
    fun mimeVectors() {
        val v = Vectors.load("mime.json")
        assertTrue(v.list("mails").isNotEmpty())
        for (c in v.list("mails")) {
            val name = c.s("name")!!
            val m = Mime.parseMail(c.s("raw")!!)
            val e = c["expect"].obj()!!
            assertEquals(e.s("subject"), m.subject, name)
            assertEquals(e.s("from"), m.from, name)
            assertEquals(e.s("text"), m.text, name)
            assertEquals(e.s("full"), m.full, name)
            assertEquals(e.s("message_id"), m.headers["message-id"], name)
            assertEquals(e.s("in_reply_to"), m.headers["in-reply-to"], name)
            assertEquals(e.s("references"), m.headers["references"], name)
            assertEquals(e.list("attachments").map { it.s("name") }, m.attachments.map { it.name }, name)
            assertEquals(e.list("attachments").map { it.s("type") }, m.attachments.map { it.type }, name)
            assertEquals(e.list("attachments").map { it["size"].int() }, m.attachments.map { it.data.size }, name)
        }
        for (a in v.list("addresses")) {
            val r = Mime.addressOf(a.s("value")!!)
            assertEquals(a.s("name"), r.name)
            assertEquals(a.s("address"), r.address)
        }
    }

    @Test
    fun buildMailRoundTrip() {
        val built = Mime.buildMail(
            fromName = "Zoë", from = "zoe@example.org", to = "ana@example.org", subject = "Re: Café ☕",
            text = "Yes **please**, à bientôt 😀\n\n" + "long line ".repeat(30), domain = "example.org",
            inReplyTo = "<a1@x>", references = "<a0@x>", now = 1793000000,
        )
        assertTrue(built.raw.lines().all { it.length <= 78 || it.startsWith("Content-Type") }, "base64 lines are wrapped")
        assertTrue(built.raw.contains("Date: Mon, 26 Oct 2026 07:33:20 +0000\r\n"), built.raw.lineSequence().first { it.startsWith("Date") })
        val m = Mime.parseMail(built.raw)
        assertEquals("Re: Café ☕", m.subject)
        assertEquals("Yes **please**, à bientôt 😀\n\n" + "long line ".repeat(30).trim(), m.text)
        assertEquals(built.messageId, m.headers["message-id"])
        assertTrue(built.messageId.endsWith("@example.org>"))
        assertEquals("<a0@x> <a1@x>", m.headers["references"])
        assertEquals("<a1@x>", m.headers["in-reply-to"])
        assertEquals("Zoë", Mime.addressOf(m.from).name)
        assertEquals("zoe@example.org", Mime.addressOf(m.from).address)
        assertEquals("<ana@example.org>", m.headers["to"])

        val plain = Mime.buildMail(fromName = "Ana \"A\"", from = "ana@example.org", to = "bob@example.net", subject = "Hello", text = "Hi", domain = "example.org")
        assertTrue(plain.raw.startsWith("From: \"Ana A\" <ana@example.org>\r\nTo: <bob@example.net>\r\nSubject: Hello\r\n"))
        assertFalse(plain.raw.contains("In-Reply-To"))
    }

    @Test
    fun mimeOddities() {
        assertEquals("Café déjà", Mime.decodeWords("=?iso-8859-1?Q?Caf=E9?= =?utf-8?B?IGTDqWrDoA==?="))
        val html = "Content-Type: text/html; charset=utf-8\r\n\r\n<html><head><style>p{}</style></head><body><p>Hi &amp; bye&#33;</p><div>Second<br>line</div><blockquote>old</blockquote></body></html>"
        assertEquals("Hi & bye!\nSecond\nline", Mime.parseMail(html).text)
        val qp = "Content-Type: text/plain; charset=iso-8859-1\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\nd=E9j=E0 vu=\r\n encore"
        assertEquals("déjà vu encore", Mime.parseMail(qp).text)
    }

    // ---- mail threads ----

    @Test
    fun mailThreadVectors() {
        val v = Vectors.load("mail-threads.json")
        assertTrue(v.list("subjects").isNotEmpty())
        for (s in v.list("subjects")) {
            val subject = s.s("subject")
            assertEquals(s.s("base"), MailThreads.baseSubject(subject), subject)
            assertEquals(s.s("thread_key_for_ana"), MailThreads.mailThreadOf("Ana@Example.ORG", subject), subject)
        }
        for (c in v.list("conversations")) {
            val conv = c.s("conv")!!
            assertEquals(c["is_new_mail"].bool(), MailThreads.isNewMail(conv), conv)
            assertEquals(c["is_mail"].bool(), MailThreads.isMail(conv), conv)
            c.s("address")?.let { assertEquals(it, MailThreads.mailAddress(conv)) }
        }
        for (seq in v.list("sequences")) {
            val known = ArrayList<KnownMail>()
            for (m in seq.list("messages")) {
                val conv = MailThreads.place(m.s("address")!!, m.s("subject"), m.s("in_reply_to"), m.s("references"), known)
                assertEquals(m.s("expect_conv"), conv, m.s("label"))
                known.add(KnownMail(m.s("message_id"), conv))
            }
        }
    }

    @Test
    fun conversationKeys() {
        assertEquals("a%20b%2Fc%C3%A9!~*'()", MailThreads.encodeURIComponent("a b/cé!~*'()"))
        assertEquals("m:ana@example.org|-", MailThreads.mailThreadOf("ana@example.org", "Re: "))
        assertEquals("n:aa,cc", MailThreads.nostrConv("cc", listOf("me", "aa"), "me"))
        assertEquals("n:me", MailThreads.nostrConv("me", listOf("me"), "me"))
        assertEquals(listOf("aa", "cc"), MailThreads.peers("n:aa,cc"))
        assertEquals(emptyList(), MailThreads.peers("m:ana@example.org"))
    }

    // ---- outbox ----

    @Test
    fun outboxVectors() = runBlocking {
        val v = Vectors.load("outbox.json")
        assertTrue(v.list("classify").isNotEmpty())
        for (c in v.list("classify")) assertEquals(c.s("result"), Outbox.classify(c.s("message")!!).key, c.s("message"))
        for (s in v.list("sequences")) {
            var items = s.list("items").map {
                OutboxItem(
                    localId = it.s("localId")!!, target = Target.of(it.s("target")!!), kind = 9, content = "", tags = emptyList(),
                    createdAt = it["created_at"].long()!!,
                    status = if (it.s("status") == "failed") OutboxStatus.FAILED else OutboxStatus.QUEUED,
                    tries = it["tries"].int() ?: 0,
                )
            }
            for (r in s.list("rounds")) {
                val retry = r.strs("retry")
                // Retry: back to queued (the web keeps the old reason until the next try)
                items = items.map { if (it.localId in retry) it.copy(status = OutboxStatus.QUEUED, error = null) else it }
                val outcomes = r["outcomes"].obj()!!
                val result = Outbox.run(items) { item ->
                    val msg = outcomes.s(item.localId)
                    if (msg != null) SendOutcome.Error(msg) else SendOutcome.Ok()
                }
                val e = r["expect"].obj()!!
                val name = s.s("name")
                assertEquals(e.strs("attempted"), result.attempted, name)
                assertEquals(e.strs("sent"), result.sent.map { it.item.localId }, name)
                val remaining = e.list("remaining")
                assertEquals(remaining.map { it.s("localId") }, result.items.map { it.localId }, name)
                assertEquals(remaining.map { it.s("status") }, result.items.map { it.status.key }, name)
                assertEquals(remaining.map { it["tries"].int() }, result.items.map { it.tries }, name)
                assertEquals(remaining.map { it.s("error") }, result.items.map { it.error }, name)
                items = result.items
            }
        }
    }

    @Test
    fun outboxRules() = runBlocking {
        assertEquals(101, Outbox.nextCreatedAt(100, 50))
        assertEquals(200, Outbox.nextCreatedAt(100, 200))
        val a = OutboxItem("a", Target.GROUPS, 9, "", emptyList(), 1)
        val b = OutboxItem("b", Target.MAIN, 9, "", emptyList(), 2)
        val c = OutboxItem("c", Target.GROUPS, 9, "", emptyList(), 3)
        val r = Outbox.run(listOf(c, b, a)) { if (it.localId == "a") SendOutcome.Error("auth-required: sign in") else SendOutcome.Ok() }
        assertEquals(listOf("a", "b"), r.attempted)
        assertEquals(setOf(Target.GROUPS), r.stalled)
        assertEquals(listOf("c", "a"), r.items.map { it.localId })
        assertEquals("Waiting for connection…", Outbox.stateText(r.items[1]))
        assertEquals("Sending…", Outbox.stateText(r.items[0]))
        val f = Outbox.run(listOf(a)) { SendOutcome.Error("You are not allowed") }
        assertEquals(listOf("a"), f.failed.map { it.localId })
        assertEquals("Not sent", Outbox.stateText(f.items[0]))
        // the item round-trips through its JSON form (what the store keeps)
        assertEquals(f.items[0], OutboxItem.fromJson(f.items[0].toJson()))
    }
}
