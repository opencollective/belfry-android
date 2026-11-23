package sh.belfry.core.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import sh.belfry.core.net.Community
import sh.belfry.core.nostr.Tag
import sh.belfry.core.nostr.s

/**
 * Printed cards (SPEC 19.72–19.74; collective-belfry docs/cards.md; canvas "Belfry · Group cards";
 * the web's `core/cards.ts` at chat f10e6b2, ported rule for rule). An A6 card with a QR code,
 * `https://chat.<domain>/g/<id>`: the first scan starts a group (upright card) or a topic (card on
 * its side) and its scanner looks after it; later scans join it through the card's guest link.
 * Everything here is pure: ids, the answers' readers, the form's rules and every word shown.
 */
enum class CardKind(val word: String) {
    GROUP("group"), TOPIC("topic");

    companion object {
        /** As the web reads it: `topic`, else a group. */
        fun of(s: String?): CardKind = if (s == "topic") TOPIC else GROUP
    }
}

/** What the belfry says about a card (`GET /cards/<id>`). */
sealed interface CardInfo {
    data class Unclaimed(val kind: CardKind) : CardInfo
    /** Someone started it: [invite] is the token of the card's guest link, [title] the group's name today. */
    data class Claimed(val kind: CardKind, val group: String, val title: String, val invite: String) : CardInfo
    data class Retired(val kind: CardKind, val title: String) : CardInfo
    data class Archived(val kind: CardKind, val title: String) : CardInfo
    /** No such card here (404), an id that is not one, an answer that is not what it should be. */
    data object Unknown : CardInfo
}

/** What the person typed on "Start this group / topic". [name] and [email] only for a visitor. */
data class CardForm(
    val title: String = "", val about: String = "", val hashtag: String = "", val name: String = "", val email: String = "",
    /** A topic card only: the summary by e-mail its starter gets, `weekly` (the default) or `monthly`. */
    val summary: String = Cards.WEEKLY,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("title", JsonPrimitive(title.take(80))); put("about", JsonPrimitive(about.take(500))); put("hashtag", JsonPrimitive(hashtag.take(30)))
        put("name", JsonPrimitive(name.take(100))); put("email", JsonPrimitive(email.take(200)))
        put("summary", JsonPrimitive(Cards.summaryOf(summary)))
    }

    companion object {
        /** The web's `keptForm`: every field cut to its length, anything else empty. */
        fun of(o: JsonObject?): CardForm? {
            if (o == null) return null
            return CardForm((o.s("title") ?: "").take(80), (o.s("about") ?: "").take(500), (o.s("hashtag") ?: "").take(30), (o.s("name") ?: "").take(100), (o.s("email") ?: "").take(200), Cards.summaryOf(o.s("summary")))
        }
    }
}

/** The web's `CardError`, one for one. */
enum class CardError {
    CLAIMED, RETIRED, NO_PASS, GUEST_NOT_SAVED, UNVERIFIED, HASHTAG_TAKEN, OWN_KEY, AGENT, KEY_TAKEN, WRONG_CODE, EXPIRED, INVALID,
    TOO_MANY, SIGNER, UNKNOWN, UNSUPPORTED, OFFLINE, FAILED,
}

/** A refusal. Only fixed, checked values are kept of the server's answer; its `message` never. */
class CardFailed(
    val code: CardError,
    /** `wrong_code`: how many tries the code has left. */
    val triesLeft: Int? = null,
    /** `invalid`: which field. */
    val field: String? = null,
    /** `claimed`: the group someone started, and the token of its guest link. */
    val group: String? = null,
    val invite: String? = null,
) : Exception(code.name)

/** A topic's hashtag (`GET /cards/hashtag/<name>`). UNKNOWN: the belfry couldn't check just now: never "free". */
enum class HashtagState { FREE, TAKEN, INVALID, UNKNOWN }

/** The first open of a group after its card (canvas cards/claim-done): who is welcomed. */
enum class CardWelcome { STARTED, JOINED }

/**
 * A card's address, `https://chat.<domain>/g/<id>` (the web also takes `#/g/<id>`). [id] is 6
 * characters of `ABCDEFGHJKMNPQRSTUVWXYZ23456789`, lower case read as upper case; anything else
 * after `/g/` gives an empty id: "This card isn't known here" (the web's `unknown`), never an
 * error about the address. Only the domain and the id are read; nothing of the address is shown.
 */
data class CardLink(val domain: String, val id: String) {
    /** False: an address of a card whose id is not one. */
    val known: Boolean get() = Cards.isId(id)

    companion object {
        private val LINK = Regex("^https://chat\\.([a-z0-9.-]+)/(?:#/)?g/([^?#]{0,200})$")

        fun parse(url: String): CardLink? {
            val m = LINK.matchEntire(url.trim()) ?: return null
            val domain = m.groupValues[1]
            if (!Community.isDomain(domain)) return null
            return CardLink(domain, Cards.cleanId(m.groupValues[2]) ?: "")
        }
    }
}

object Cards {
    /** 6 characters, capitals and digits without 0 O 1 I L (read aloud). */
    private val ID = Regex("^[A-HJKMNP-Z2-9]{6}$")
    private val INVITE = Regex("^[A-Za-z0-9_-]{20,200}$")
    private val GROUP = Regex("^[A-Za-z0-9_-]{1,120}$")
    private val FIELD = Regex("^[a-z_]{1,20}$")
    private val EMAIL = Regex("^\\S+@\\S+\\.\\S+$")
    private val CONTROL = Regex("[\\u0000-\\u001f\\u007f\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]")

    fun isId(s: String): Boolean = ID.matches(s)
    fun isGroup(s: String): Boolean = GROUP.matches(s)
    fun isInvite(s: String): Boolean = INVITE.matches(s)

    /** What follows `/g/` as an id: one trailing slash dropped, read as upper case; null when it is not an id. */
    fun cleanId(raw: String): String? = raw.removeSuffix("/").uppercase().takeIf(::isId)

    /** The card's address (its QR code). */
    fun url(domain: String, id: String): String = "https://chat.$domain/g/$id"

    /** The web's `plainLabel`: control and direction characters out, trimmed, 100 at most. */
    fun plainLabel(t: String): String = t.replace(CONTROL, "").trim().take(100)

    /** The hashtag as a channel id (the web's `hashtagSlug`): lower case, letters, digits and dashes, 30 at most. */
    fun hashtagSlug(s: String): String =
        s.trim().replace(Regex("^#+"), "").lowercase().replace(Regex("[^a-z0-9-]+"), "-").replace(Regex("^-+|-+$"), "").take(30)

    /** The Nth name offered when [slug] is taken: `slug-2`, `slug-3`… cut so that it stays 30 at most. */
    fun nextHashtag(slug: String, n: Int): String = slug.take(30 - n.toString().length - 1) + "-" + n

    /**
     * A suggested hashtag is taken: the next free one, `-2` to `-9` asked in turn (the web's rule).
     * Null when none is free, or when an answer is anything but taken / free (the belfry couldn't
     * check: nothing is offered).
     */
    suspend fun nextFree(slug: String, check: suspend (String) -> HashtagState): String? {
        for (n in 2..9) {
            val next = nextHashtag(slug, n)
            when (check(next)) {
                HashtagState.FREE -> return next
                HashtagState.TAKEN -> continue
                else -> return null
            }
        }
        return null
    }

    /** `GET /cards/hashtag/<name>` read: 5xx and 429 are "couldn't check", a state that is neither free nor taken is invalid. */
    fun hashtagAnswer(status: Int, body: JsonObject?): HashtagState {
        if (status >= 500 || status == 429) return HashtagState.UNKNOWN
        return when (body?.s("state")) {
            "free" -> HashtagState.FREE
            "taken" -> HashtagState.TAKEN
            else -> HashtagState.INVALID
        }
    }

    /** The line under the hashtag field. EDITED: the person changed the suggestion by hand. Null: nothing to say. */
    fun hashtagLine(state: HashtagState?, checking: Boolean, edited: Boolean): String? = when {
        checking -> "Checking…"
        state == HashtagState.FREE -> if (edited) "✓ Available" else "✓ Available · suggested from the topic, you can change it"
        state == HashtagState.TAKEN -> "Taken: try another one"
        state == HashtagState.INVALID -> "Letters, digits and dashes, 2 to 30"
        state == HashtagState.UNKNOWN -> "Couldn’t check just now"
        else -> null
    }

    /** A `GET /cards/<id>` answer (status 200), kept only when every field is what it should be. */
    fun infoOf(d: JsonObject): CardInfo {
        val kind = CardKind.of(d.s("kind"))
        val title = plainLabel(d.s("title") ?: "").take(80)
        val group = d.s("group")
        val invite = d.s("invite")
        return when (d.s("state")) {
            "unclaimed" -> CardInfo.Unclaimed(kind)
            "claimed" -> if (group != null && isGroup(group) && invite != null && isInvite(invite)) CardInfo.Claimed(kind, group, title, invite) else CardInfo.Unknown
            "retired" -> CardInfo.Retired(kind, title)
            "archived" -> CardInfo.Archived(kind, title)
            else -> CardInfo.Unknown
        }
    }

    private val KNOWN = mapOf(
        "claimed" to CardError.CLAIMED, "retired" to CardError.RETIRED, "no_pass" to CardError.NO_PASS, "guest_not_saved" to CardError.GUEST_NOT_SAVED,
        "unverified" to CardError.UNVERIFIED, "hashtag_taken" to CardError.HASHTAG_TAKEN, "own_key" to CardError.OWN_KEY, "agent" to CardError.AGENT,
        "key_taken" to CardError.KEY_TAKEN, "wrong_code" to CardError.WRONG_CODE, "expired" to CardError.EXPIRED, "invalid" to CardError.INVALID,
        "too_many" to CardError.TOO_MANY, "signer" to CardError.SIGNER, "relay" to CardError.SIGNER, "unsupported" to CardError.UNSUPPORTED,
    )

    /** The web's `cardFailure`: what a refusal means, by its status and `error` code. */
    fun failure(status: Int, d: JsonObject?): CardFailed {
        if (status == 404) return CardFailed(CardError.UNKNOWN)
        if (status == 429) return CardFailed(CardError.TOO_MANY)
        val code = KNOWN[d?.s("error") ?: ""] ?: CardError.FAILED
        val left = (d?.get("tries_left") as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it in 0..99 }
        return CardFailed(
            code,
            triesLeft = left,
            field = d?.s("field")?.takeIf { FIELD.matches(it) },
            group = d?.s("group")?.takeIf(::isGroup),
            invite = d?.s("invite")?.takeIf(::isInvite),
        )
    }

    /**
     * The words for a refusal: the app's own (the web's `cardErrorText`), never the server's text.
     * AFTER_CODE: said on the code screen, where a busy belfry has spent the code (docs/cards.md
     * §2.3: "ask for a new one").
     */
    fun errorText(f: CardFailed, kind: CardKind = CardKind.GROUP, afterCode: Boolean = false): String {
        val k = kind.word
        if (f.code == CardError.WRONG_CODE && f.triesLeft != null) {
            return if (f.triesLeft <= 0) "That code is not right. Send a new one." else "That code is not right. ${f.triesLeft} ${if (f.triesLeft == 1) "try" else "tries"} left."
        }
        if (f.code == CardError.INVALID) return when (f.field) {
            "email" -> "That doesn’t look like an e-mail address."
            "hashtag" -> "Use letters, digits and dashes, 2 to 30 characters."
            "name" -> "Please give your name."
            else -> "Please give the $k’s name, up to 80 characters."
        }
        return when (f.code) {
            CardError.CLAIMED -> "Someone just started this $k."
            CardError.RETIRED -> "This card was retired: it doesn’t start a $k any more."
            CardError.NO_PASS -> "Only members of this belfry can start a $k while signed in. Sign out to start it as a guest with your e-mail."
            CardError.GUEST_NOT_SAVED -> "Save your account with your e-mail first, then scan the card again."
            CardError.UNVERIFIED -> "Confirm your e-mail address first, then scan the card again."
            CardError.HASHTAG_TAKEN -> "This hashtag is taken. Try another one."
            CardError.OWN_KEY -> "This address has an account that signs in with its own key. Sign in with it, then scan the card again."
            CardError.AGENT -> "An AI agent can’t start it. Use a person’s account."
            CardError.KEY_TAKEN -> "That did not work. Send a new code and try again."
            CardError.WRONG_CODE -> "That code is not right. Check the e-mail and try again."
            CardError.EXPIRED -> "That code has expired. Send a new one."
            CardError.TOO_MANY -> "Too many tries for now. Try again in a few minutes."
            CardError.SIGNER -> if (afterCode) "The belfry is busy. Send a new code in a minute and try again." else "The belfry is busy. Try again in a minute."
            CardError.UNKNOWN -> "This card isn’t known here."
            CardError.UNSUPPORTED -> "This belfry doesn’t take printed cards."
            CardError.OFFLINE -> "No connection. Try again."
            CardError.INVALID -> "Please check the form."
            CardError.FAILED -> "That did not work. Try again."
        }
    }

    /** These refusals of the code leave nothing to type again: the boxes are emptied (the web's `verify`). */
    fun clearsCode(code: CardError): Boolean = code == CardError.WRONG_CODE || code == CardError.EXPIRED || code == CardError.KEY_TAKEN || code == CardError.SIGNER

    fun isEmail(s: String): Boolean = EMAIL.matches(s.trim())

    /** May "Start the group / topic" be tapped? A topic needs its hashtag checked free; a visitor a name and an e-mail. */
    fun ready(kind: CardKind, f: CardForm, signedIn: Boolean, tag: HashtagState?): Boolean =
        f.title.isNotBlank() && (kind != CardKind.TOPIC || tag == HashtagState.FREE) && (signedIn || (f.name.isNotBlank() && isEmail(f.email)))

    /** The form typed with a suggested hashtag: was the hashtag changed by hand (kept forms open as the web's do)? */
    fun hashtagEdited(f: CardForm): Boolean = f.hashtag.isNotEmpty() && f.hashtag != hashtagSlug(f.title)

    /** The body of `POST /cards/<id>/claim`. */
    fun claimBody(kind: CardKind, f: CardForm): JsonObject = buildJsonObject {
        put("title", JsonPrimitive(plainLabel(f.title).take(80)))
        put("about", JsonPrimitive(f.about.trim().take(500)))
        if (kind == CardKind.TOPIC) {
            put("hashtag", JsonPrimitive(hashtagSlug(f.hashtag)))
            // topic cards only, never on a group card: the starter's summary by e-mail
            put("summary", JsonPrimitive(summaryOf(f.summary)))
        }
    }

    /** The body of `POST /cards/<id>/claim-verify` (the web's, with this app's `client`). */
    fun verifyBody(kind: CardKind, f: CardForm, code: String, client: String = "belfry-android"): JsonObject = buildJsonObject {
        put("email", JsonPrimitive(f.email.trim()))
        put("code", JsonPrimitive(code.trim()))
        put("name", JsonPrimitive(plainLabel(f.name).take(100)))
        put("client", JsonPrimitive(client))
        for ((k, v) in claimBody(kind, f)) put(k, v)
    }

    // ---- a topic's summary by e-mail (canvas cards/claim-channel) ------------------------------------

    const val WEEKLY = "weekly"
    const val MONTHLY = "monthly"
    /** `monthly`, else `weekly` (the default, and what anything else reads as). */
    fun summaryOf(s: String?): String = if (s == MONTHLY) MONTHLY else WEEKLY
    const val SUMMARY_LABEL = "Summary by e-mail"
    fun summaryHint(summary: String): String =
        if (summaryOf(summary) == MONTHLY) "What was said, once a month. Change it any time." else "What was said, once a week. Change it any time."

    /** A topic made from a card, as the server makes it: its description is `<topic> · <description>`. */
    fun topicAbout(f: CardForm): String = listOf(plainLabel(f.title).take(80), f.about.trim().take(500)).filter { it.isNotEmpty() }.joinToString(" · ")

    // ---- the screens' words (the web as shipped, where a draft board says otherwise) ----------------

    fun startTitle(kind: CardKind): String = "Start this ${kind.word}"
    fun startIntro(kind: CardKind): String =
        if (kind == CardKind.TOPIC) "You found a new topic card. No introductions needed: people come and go and share what they know."
        else "You found a new group card. You start the group and look after it; everyone who scans this card after you joins it."
    fun startButton(kind: CardKind): String = "Start the ${kind.word}"
    fun titleLabel(kind: CardKind): String = if (kind == CardKind.TOPIC) "Topic" else "Title"
    const val TITLE_HINT = "What you wrote on the card"
    fun codeHint(community: String): String = "We’ll send a 6-digit code to confirm it. Already have an account on $community? Use its e-mail address."

    fun takenTitle(kind: CardKind): String = "Someone just started this ${kind.word}"
    const val TAKEN_TEXT = "Your text wasn’t used; you can join theirs."
    fun takenButton(kind: CardKind): String = if (kind == CardKind.TOPIC) "Open the topic" else "Join"

    fun retiredTitle(kind: CardKind, title: String): String = "${title.ifEmpty { "This ${kind.word}" }} is closed to new people"
    fun retiredText(kind: CardKind): String = "The card was retired. People already in the ${kind.word} carry on there."
    fun archivedTitle(kind: CardKind, title: String): String = "${title.ifEmpty { "This ${kind.word}" }} has ended"
    fun archivedText(kind: CardKind): String = "The ${kind.word} was archived."
    const val UNKNOWN_TITLE = "This card isn’t known here"
    fun unknownText(domain: String): String = "Check that you scanned a card of $domain."
    const val OFFLINE_TITLE = "No connection"
    const val OFFLINE_TEXT = "Belfry needs the internet to open the card. What you typed stays here."
    const val BUSY_TITLE = "Too many tries for now"
    const val BUSY_TEXT = "Try again in a few minutes. What you typed stays here."
    const val UNSUPPORTED_TITLE = "This belfry doesn’t take printed cards"
    const val OPENING = "Opening the card…"

    /** The welcome of the first open after a card (the web's `GroupMid`): the bold first words, then the rest. */
    fun welcomeTitle(pod: Boolean, name: String): String = "Welcome to ${if (pod) name else "# $name"}!"
    fun welcomeText(pod: Boolean, welcome: CardWelcome): String = when {
        pod && welcome == CardWelcome.STARTED -> "You started it. Say hi and share something to get it going: a photo, a tip, a question."
        pod -> "Say hi and share something: a photo, a tip, a question."
        welcome == CardWelcome.STARTED -> "You started it. Share something to get it going: a link, a photo, a question."
        else -> "No introductions needed: share what you know."
    }

    // ---- hooks for what is not built here ------------------------------------------------------------

    /**
     * The groups key's first line in a group started from a card (kind 9 tagged `["card","1"]`,
     * docs/cards.md §2.2 step 4): a system line, never a person's message (the web's `isCardLine`).
     */
    fun isCardLine(pubkey: String, tags: List<Tag>, groupsKey: String?): Boolean =
        groupsKey != null && pubkey == groupsKey && tags.any { it.size >= 2 && it[0] == "card" && it[1] == "1" }

    /** That line in the app's words: never the event's text, never the card's id. WHO: the name to say ("You" for me), null when the event names nobody. */
    fun cardLine(who: String?, pod: Boolean): String {
        val word = if (pod) "group" else "topic"
        return if (who != null) "$who started this $word from a printed card" else "This $word was started from a printed card"
    }
}
