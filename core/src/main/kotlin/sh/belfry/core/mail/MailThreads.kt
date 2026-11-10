package sh.belfry.core.mail

import sh.belfry.core.markdown.Js
import java.util.regex.Pattern

// Conversation keys for direct messages and e-mail threads (SPEC 10), from src/core/dm.ts.
//   n:<pubkey>[,<pubkey>…]    a Nostr conversation (sorted keys, me excluded)
//   m:<address>|<subject key> an e-mail thread
//   m:<address>               a new e-mail, a thread once sent

/** A mail already placed in a thread: its Message-ID and the conversation it is in. */
data class KnownMail(val messageId: String?, val conv: String)

object MailThreads {
    private const val W = Js.WS
    private val PREFIX = Pattern.compile("^[$W]*((re|fwd?|aw|sv|tr|wg|antw|rif|r)[$W]*(\\[\\d+\\])?[$W]*:[$W]*)+", Pattern.CASE_INSENSITIVE)
    private val SPACES = Pattern.compile("[$W]+")
    private val MESSAGE_IDS = Pattern.compile("<[^<>$W]+>")
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"

    /** The subject without leading Re: / Fwd: / AW: … prefixes. */
    fun baseSubject(s: String?): String = Js.trim(PREFIX.matcher(s ?: "").replaceFirst(""))

    /** encodeURIComponent, as JavaScript does it (a lone surrogate becomes U+FFFD instead of throwing). */
    fun encodeURIComponent(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xff).toChar()
            if (b >= 0 && UNRESERVED.indexOf(c) >= 0) sb.append(c)
            else sb.append('%').append("0123456789ABCDEF"[(b.toInt() and 0xff) ushr 4]).append("0123456789ABCDEF"[b.toInt() and 0x0f])
        }
        return sb.toString()
    }

    fun subjectKey(s: String?): String {
        val k = encodeURIComponent(SPACES.matcher(baseSubject(s).lowercase()).replaceAll(" "))
        return k.ifEmpty { "-" }
    }

    /** The conversation of an e-mail thread: `m:<address>|<subject key>`. */
    fun mailThreadOf(address: String, subject: String?): String = "m:" + address.lowercase() + "|" + subjectKey(subject)

    fun isMail(conv: String): Boolean = conv.startsWith("m:")

    /** `m:<address>` alone: a new e-mail not sent yet. */
    fun isNewMail(conv: String): Boolean = conv.startsWith("m:") && !conv.contains("|")

    /** The address of an e-mail conversation. */
    fun mailAddress(conv: String): String = conv.drop(2).split("|")[0]

    /** The other people of a Nostr conversation. */
    fun peers(conv: String): List<String> = if (conv.startsWith("n:")) conv.drop(2).split(",").filter { it.isNotEmpty() } else emptyList()

    /** `n:` + everyone in the rumor (author + p tags) minus me, sorted; `n:<me>` for notes to self. */
    fun nostrConv(author: String, pTags: List<String>, me: String): String {
        val people = (listOf(author) + pTags).toSortedSet()
        people.remove(me)
        return "n:" + (if (people.isEmpty()) me else people.joinToString(","))
    }

    /** The `<id>`s of a Message-ID / In-Reply-To / References header, in order. */
    fun messageIds(header: String?): List<String> {
        val out = ArrayList<String>()
        val m = MESSAGE_IDS.matcher(header ?: "")
        while (m.find()) out.add(m.group())
        return out
    }

    /**
     * The thread a mail joins: the one its In-Reply-To (first) or References (last to first) name
     * among [known] mails with the same address, else the thread of its subject.
     */
    fun place(address: String, subject: String?, inReplyTo: String?, references: String?, known: List<KnownMail>): String {
        val a = address.lowercase()
        val refs = (messageIds(references) + messageIds(inReplyTo)).reversed()
        for (id in refs) {
            for (k in known) if (k.messageId == id && isMail(k.conv) && mailAddress(k.conv) == a) return k.conv
        }
        return mailThreadOf(a, subject)
    }
}
