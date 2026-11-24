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
    /**
     * A visitor: when the last code was asked (`claim-start`, seconds; 0 = not yet). While it is
     * less than [Cards.CLAIM_SECONDS] old the card opens again on the waiting screen (SPEC 19.89).
     */
    val sentAt: Long = 0,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("title", JsonPrimitive(title.take(80))); put("about", JsonPrimitive(about.take(500))); put("hashtag", JsonPrimitive(hashtag.take(30)))
        put("name", JsonPrimitive(name.take(100))); put("email", JsonPrimitive(email.take(200)))
        put("summary", JsonPrimitive(Cards.summaryOf(summary)))
        if (sentAt > 0) put("sentAt", JsonPrimitive(sentAt))
    }

    companion object {
        /** The web's `keptForm`: every field cut to its length, anything else empty. */
        fun of(o: JsonObject?): CardForm? {
            if (o == null) return null
            val sent = (o["sentAt"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()?.takeIf { it > 0 } ?: 0
            return CardForm((o.s("title") ?: "").take(80), (o.s("about") ?: "").take(500), (o.s("hashtag") ?: "").take(30), (o.s("name") ?: "").take(100), (o.s("email") ?: "").take(200), Cards.summaryOf(o.s("summary")), sent)
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
data class CardLink(
    val domain: String,
    val id: String,
    /**
     * The e-mail's Confirm button (`?confirm=<token>`, SPEC 19.89): the token, kept in memory only
     * (never in the vault, never shown), spent once by `claim-confirm`. Null on a card's own address.
     */
    val confirm: String? = null,
) {
    /** False: an address of a card whose id is not one. */
    val known: Boolean get() = Cards.isId(id)

    /** The card itself, without the Confirm link's token (what may be kept). */
    val card: CardLink get() = if (confirm == null) this else copy(confirm = null)

    override fun toString(): String = "CardLink($domain, $id${if (confirm != null) ", confirm" else ""})" // never the token in a log

    companion object {
        private val LINK = Regex("^https://chat\\.([a-z0-9.-]+)/(?:#/)?g/([^?#]{0,200})(?:\\?([^#]{0,600}))?$")
        private val TOKEN = Regex("^[A-Za-z0-9_-]{8,200}$")

        fun parse(url: String): CardLink? {
            val m = LINK.matchEntire(url.trim()) ?: return null
            val domain = m.groupValues[1]
            if (!Community.isDomain(domain)) return null
            // of the query only `confirm`, and only a well-formed token (the web's takeCardFromUrl)
            val confirm = m.groupValues[3].split('&').firstNotNullOfOrNull { p ->
                p.takeIf { it.startsWith("confirm=") }?.removePrefix("confirm=")?.takeIf(TOKEN::matches)
            }
            val id = Cards.cleanId(m.groupValues[2]) ?: ""
            return CardLink(domain, id, confirm?.takeIf { id.isNotEmpty() })
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
        s.trim().replace(Regex("^#+"), "").lowercase().replace(Regex("[^a-z0-9-]+"), "-").replace(Regex("^-+|-+$"), "").take(30).trimEnd('-') // a cut at 30 must not leave a dash at the end (the server calls that invalid)

    /** `GET /cards/hashtag/<name>` read: 5xx and 429 are "couldn't check", a state that is neither free nor taken is invalid. */
    fun hashtagAnswer(status: Int, body: JsonObject?): HashtagState {
        if (status >= 500 || status == 429) return HashtagState.UNKNOWN
        return when (body?.s("state")) {
            "free" -> HashtagState.FREE
            "taken" -> HashtagState.TAKEN
            else -> HashtagState.INVALID
        }
    }

    /**
     * The line under the hashtag field (SPEC 19.89: the hashtag is asked directly, it is the
     * channel's name; nothing is suggested). Null: nothing to say.
     */
    fun hashtagLine(state: HashtagState?, checking: Boolean): String? = when {
        checking -> "Checking…"
        state == HashtagState.FREE -> "✓ Available · this is the channel’s name"
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

    /**
     * May "Create the group / topic" be tapped? A group needs its name; a topic only its hashtag,
     * checked free (it has no other name, SPEC 19.89); a visitor a name and an e-mail too.
     */
    fun ready(kind: CardKind, f: CardForm, signedIn: Boolean, tag: HashtagState?): Boolean =
        (if (kind == CardKind.TOPIC) tag == HashtagState.FREE else f.title.isNotBlank()) && (signedIn || (f.name.isNotBlank() && isEmail(f.email)))

    /**
     * The form's part of every claim body (`claim`, `claim-start`, `claim-verify`): a group's
     * `title` and `about`; a topic's `about` and `hashtag` and no title (the belfry names it by its
     * hashtag, its description is `about` alone: docs/cards.md §2.2).
     */
    fun claimBody(kind: CardKind, f: CardForm): JsonObject = buildJsonObject {
        if (kind != CardKind.TOPIC) put("title", JsonPrimitive(plainLabel(f.title).take(80)))
        put("about", JsonPrimitive(f.about.trim().take(500)))
        if (kind == CardKind.TOPIC) {
            put("hashtag", JsonPrimitive(hashtagSlug(f.hashtag)))
            // no "summary" (Xavier, Cards v80: "remove, it's ok to keep it in the settings"): the server
            // starts the topic's starter on weekly; it is changed in the topic's notification settings
        }
    }

    /**
     * The body of `POST /cards/<id>/claim-start` (SPEC 19.89): the whole form, kept by the belfry
     * for 15 minutes so that the e-mail's Confirm button can create it; `client` names the session
     * the button hands out.
     */
    fun startBody(kind: CardKind, f: CardForm, client: String = "belfry-android"): JsonObject = buildJsonObject {
        put("email", JsonPrimitive(f.email.trim()))
        put("name", JsonPrimitive(plainLabel(f.name).take(100)))
        put("client", JsonPrimitive(client))
        for ((k, v) in claimBody(kind, f)) put(k, v)
    }

    /** The body of `POST /cards/<id>/claim-confirm`: the Confirm link's token, nothing else. */
    fun confirmBody(token: String): JsonObject = buildJsonObject { put("token", JsonPrimitive(token)) }

    /** The body of `POST /cards/<id>/claim-poll`. */
    val pollBody: JsonObject = JsonObject(emptyMap())

    /** The belfry keeps a claim (its code, its Confirm button) for 15 minutes from `claim-start`. */
    const val CLAIM_SECONDS = 900L
    /** The waiting screen asks `claim-poll` every 3 s while it is shown (the web's interval). */
    const val POLL_MS = 3_000L
    /** At most this many asks per code sent (15 minutes of 3 s), whatever the clock says. */
    const val POLL_MAX = (CLAIM_SECONDS * 1000 / POLL_MS).toInt()

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

    /** A topic made from a card, as the server makes it now (no title): its description is the description alone. */
    fun topicAbout(f: CardForm): String = f.about.trim().take(500)

    // ---- the screens' words (the web as shipped, where a draft board says otherwise) ----------------

    fun startTitle(kind: CardKind): String = "Start a ${kind.word}"
    fun startIntro(kind: CardKind): String = "Nobody has scanned this card yet. The ${kind.word} starts with you."
    fun startButton(kind: CardKind): String = "Create the ${kind.word}"
    /** The card's chip on the form (onb-group-form, onb-topic-form): "Card K7Q2MX", "Card X2M7KE · topic". */
    fun chip(kind: CardKind, id: String): String = "Card $id" + if (kind == CardKind.TOPIC) " · topic" else ""
    const val GROUP_NAME = "Group name"
    const val TITLE_HINT = "What you wrote on the card"
    const val HASHTAG = "Hashtag"
    const val DESCRIPTION = "Description"
    const val YOUR_NAME = "Your name"
    const val YOUR_EMAIL = "Your e-mail"
    const val LOG_OUT = "Log out"
    /** Under Create: a visitor's line, or a signed-in account's (Xavier's footnote on cards/claim-signed-in). */
    fun startFoot(kind: CardKind, signedIn: Boolean): String = when {
        !signedIn -> "You’ll look after this ${kind.word}. We’ll send a 6-digit code to confirm your e-mail."
        kind == CardKind.TOPIC -> "You’ll look after this topic."
        else -> "You’ll be the group’s first steward.\n\nPeople who scan the same QR code again will be invited to join this group so that you can remain connected after this event without having to share your email address publicly."
    }
    /** What is being made, in the code screen's words: the group's name, a topic's "#hashtag". */
    fun what(kind: CardKind, f: CardForm): String = if (kind == CardKind.TOPIC) "#" + hashtagSlug(f.hashtag) else plainLabel(f.title).take(80)

    // the code screen (onb-code)
    const val CODE_TITLE = "Check your e-mail"
    fun codeLead(what: String, email: String): String = "To create $what, enter the 6-digit code we sent to $email."
    /** The board says "click … This page"; a phone taps, and this is a screen. */
    const val CODE_OR_CONFIRM = "or tap Confirm in the e-mail. This screen moves on by itself once you have."
    fun creating(what: String): String = "Creating $what…"
    const val SPAM = "Not there? Look in your spam folder."
    const val NEW_CODE_SENT = "A new code is on its way to your e-mail."
    fun resend(wait: Int): String = if (wait > 0) "Send a new code (in $wait s)" else "Send a new code"
    const val OTHER_ADDRESS = "Use another address"

    /**
     * What a refusal of `claim-poll` says on the waiting screen (it stops asking): 410 the claim ran
     * out (or was used), 404 the belfry has no claim of this key for this card any more. Anything
     * else is asked again at the next tick and says nothing.
     */
    fun pollErrorText(f: CardFailed): String? = when (f.code) {
        CardError.EXPIRED -> "The code and the Confirm button have expired. Send a new code."
        CardError.UNKNOWN -> "This request isn’t known any more. Send a new code."
        else -> null
    }
    /** Does this refusal of `claim-poll` end the waiting (no more asks)? */
    fun pollStops(f: CardFailed): Boolean = f.code == CardError.EXPIRED || f.code == CardError.UNKNOWN

    // the e-mail's Confirm button opened in the app (onb-mail → onb-group-done, or onb-confirmed-elsewhere)
    const val CONFIRMING = "Confirming…"
    const val CONFIRMED_TITLE = "Confirmed"
    const val CONFIRMED_TEXT = "It’s created. Go back to the page where you scanned the card: it opens there by itself."
    const val CONFIRM_FAILED_TITLE = "This link didn’t work"
    /** Why the Confirm link didn't work, in the app's words; ends with what to do. */
    fun confirmErrorText(f: CardFailed, kind: CardKind = CardKind.GROUP): String = when (f.code) {
        CardError.EXPIRED -> "It has expired, or it was used already. Scan the card again to start over."
        CardError.UNKNOWN -> "This card isn’t known here. Scan the card again to start over."
        CardError.CLAIMED -> "Someone else was first with this card. Scan it again to join theirs."
        CardError.RETIRED -> "This card was retired."
        CardError.SIGNER -> "The belfry is busy. Try again in a minute: the link still works."
        CardError.OFFLINE -> "No connection. Try again: the link still works."
        CardError.TOO_MANY -> "Too many tries for now. Try again in a few minutes."
        else -> errorText(f, kind) + " Scan the card again to start over."
    }
    /** These Confirm refusals leave the link usable: Try again (docs/cards.md §2.3: "nothing is used"). */
    fun confirmMayRetry(f: CardFailed): Boolean = f.code == CardError.SIGNER || f.code == CardError.OFFLINE || f.code == CardError.TOO_MANY

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

    /** The welcome of the first open after a card (onb-group-done, onb-topic-done; the web's `GroupMid`): the bold first words, then the rest. */
    fun welcomeTitle(pod: Boolean, name: String, welcome: CardWelcome = CardWelcome.STARTED): String = when {
        pod -> "Welcome to $name!"
        welcome == CardWelcome.STARTED -> "#$name is open!"
        else -> "Welcome to #$name!"
    }
    fun welcomeText(pod: Boolean, welcome: CardWelcome): String = when {
        pod && welcome == CardWelcome.STARTED -> "You created it and you look after it. Say hi and share something to get it going."
        pod -> "Say hi and share something: a photo, a tip, a question."
        welcome == CardWelcome.STARTED -> "You created it and you look after it. Share the first tip, link or question."
        else -> "No introductions needed: share what you know."
    }
    /** The facts under the description on the done screens: "Steward: you · 1 person" / "Steward: you · 1 following". */
    fun stewardFacts(pod: Boolean, steward: String, count: Int): String =
        "Steward: $steward · " + if (pod) (if (count == 1) "1 person" else "$count people") else "$count following"
    /** The card's line on the done screens: "Card K7Q2MX: anyone who scans it joins here". */
    fun cardFacts(pod: Boolean, id: String): String =
        "Card $id: " + if (pod) "anyone who scans it joins here" else "anyone who scans it can read, post and follow"

    // ---- hooks for what is not built here ------------------------------------------------------------

    /**
     * The groups key's first line in a group started from a card (kind 9 tagged `["card","1"]`,
     * docs/cards.md §2.2 step 4): a system line, never a person's message (the web's `isCardLine`).
     */
    fun isCardLine(pubkey: String, tags: List<Tag>, groupsKey: String?): Boolean =
        groupsKey != null && pubkey == groupsKey && tags.any { it.size >= 2 && it[0] == "card" && it[1] == "1" }

    /** That line in the app's words (never the event's text): [CardAuthors.startedLine]. */
    fun cardLine(who: String?, pod: Boolean, cardId: String? = null): String = CardAuthors.startedLine(who, pod, cardId)
}
