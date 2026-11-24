package sh.belfry.core.enc

/**
 * Why a write waits on this device (docs/encrypted.md §3, §9 "Held", §17.1, §17.4, §17.5, §18.5,
 * §23, §25; the web's `Held` reasons). The text is kept; the write goes when the reason is gone.
 */
enum class HoldReason {
    /** The belfry's format is unknown here, or a 1 read more than 5 minutes ago (re-read first). */
    FORMAT,
    /** E-mail out on format 3 before the mail server's format-3 build (no longer used: SPEC 19.83 sends it). */
    MAIL,
    /** A guest in a conversation that was encrypted (§25); a guest's session in a private group. */
    GUEST,
    /** This device is not on its account's current list (yet). */
    NOT_LISTED,
    /** §17.4: twenty devices are listed and this one is not. */
    FULL,
    /** A person's list could not be read, or none is known (§17.1: never the account key). */
    NO_LIST,
    /** §17.7 / §18.5: a person's lists forked, or a newer signed list isn't valid. */
    FORK,
    /** §17.5: the only other person has no device. */
    NO_DEVICE,
    /** Over 100 devices in a conversation that was encrypted (§17.2), over 1000 in a private group (§4). */
    TOO_MANY,
    /** The group isn't known here yet, or its members could not be checked. */
    GROUP_UNKNOWN,
}

/** One person of a format-3 people message: the list it is made for (none for a service key) and who receives copies. */
data class PersonDevices(val account: String, val list: String?, val devices: List<String>)

/** What part 3 encrypts for: a people conversation's lists, or a private group's devices. */
sealed interface EncPlan {
    /** PER: every person (me included), MISSING: people with no device who won't get it (§17.5), LISTS: [EncKeys.peopleLists] of the key to use. */
    data class People(val per: List<PersonDevices>, val missing: List<String>, val lists: String) : EncPlan {
        val total: Int get() = per.sumOf { it.devices.size }
    }

    /** DEVICES: every entry of the key (§16.1); LINES: account → its current list id (the envelope's `lists` hash); SET: [EncKeys.deviceSet]. */
    data class Group(val devices: List<String>, val lines: List<Pair<String, String>>, val set: String) : EncPlan
}

/** What a write in a conversation does now (SPEC 19.82). */
sealed interface EncWrite {
    /** The version-1 ways, exactly as before (a group's plain event; sealed v1 or NIP-17 as the sealed engine decides). */
    data object V1 : EncWrite

    /** Format 3, encrypted. PLAN once the lists were checked (null: the table alone, before the checks). */
    data class V3(val plan: EncPlan? = null) : EncWrite

    /**
     * Unencrypted where format 3 allows it (no lock): a people conversation that starts over 100 devices or
     * includes a guest and was never encrypted (NIP-17, never sealed v1), a group that isn't private, a §16.9
     * kind in a private group.
     */
    data object Clear : EncWrite

    /** Held on this device with its text (WHO: the person it is about, when there is one). */
    data class Hold(val reason: HoldReason, val who: String? = null) : EncWrite
}

/**
 * The write-decision table (a port of the web's `deliver` / `encryptFor` guest rule for people
 * conversations and `model.groupWriteMode` + the guest guard for groups; branch enc-v3, 515400d).
 * Pure: the caller gives the facts; the format is [EncFormatState.writeFormat] (3; a 1 read in the
 * last 5 minutes; 1 at once when this device never saw an `encrypted` object from this belfry — the
 * web's P3; else null).
 */
object EncWrites {
    /** DEMO: everything stays format 1. GUEST_SESSION: a guest's session runs no format-3 runtime (§25). FORMAT: as above. */
    data class Facts(val demo: Boolean, val guestSession: Boolean, val format: Int?)

    /**
     * A people conversation (`n:` …) or an e-mail thread (`m:` …) of mine. EVER: this device has seen it
     * encrypted (§17.2, §18.6). GUEST_IN: a guest is among its people (§25).
     */
    fun people(conv: String, f: Facts, ever: Boolean, guestIn: Boolean): EncWrite {
        if (f.demo) return EncWrite.V1
        val mail = conv.startsWith("m:")
        val people = conv.startsWith("n:")
        if (!mail && !people) return EncWrite.V1
        val enc = people && ever
        if (f.format == null && !enc) return EncWrite.Hold(HoldReason.FORMAT)
        // e-mail out on format 3 (SPEC 19.83; §10, the lead 2026-11-24: live on the server): an 8572 with the belfry key
        // as one more person of that message; a guest's session runs no format 3 at all (§25)
        if (mail) return if (f.format == 3) (if (f.guestSession) EncWrite.Hold(HoldReason.GUEST) else EncWrite.V3()) else EncWrite.V1
        if (f.format == 3 || enc) {
            if (f.guestSession || guestIn) return if (enc) EncWrite.Hold(HoldReason.GUEST) else EncWrite.Clear
            return EncWrite.V3()
        }
        return EncWrite.V1
    }

    /**
     * A group's write. EVER: a key of the group was seen here (§26: encrypted whatever the format);
     * PRIVATE_META: its metadata says private (a group, closed + private); KNOWN: its metadata is here;
     * CLEAR_KIND: the event is on §16.9's clear list ([EncRules.clearInGroup]).
     */
    fun group(f: Facts, ever: Boolean, privateMeta: Boolean, known: Boolean, clearKind: Boolean): EncWrite {
        if (f.demo) return EncWrite.V1
        if (clearKind) return if (f.format == 3 || ever) EncWrite.Clear else EncWrite.V1
        val mode: EncWrite = when {
            ever -> EncWrite.V3()
            f.format == 3 -> if (privateMeta) EncWrite.V3() else if (known) EncWrite.Clear else EncWrite.Hold(HoldReason.GROUP_UNKNOWN)
            f.format == null -> if (privateMeta) EncWrite.Hold(HoldReason.FORMAT) else if (!known) EncWrite.Hold(HoldReason.FORMAT) else EncWrite.V1
            else -> EncWrite.V1
        }
        // c6b69f9: a guest's session can't take part in a private group (encrypted, or waiting to be)
        if (f.guestSession && (mode is EncWrite.V3 || (mode is EncWrite.Hold && privateMeta))) return EncWrite.Hold(HoldReason.GUEST)
        return mode
    }
}

/** The words of format 3's holds and of this device's set-up line (wording not final: part 4 draws the screens). */
object EncText {
    /** The subline under Notes to myself's name on a format-3 belfry (Xavier, Encrypted canvas v33 encrypted/notes-self). */
    fun notesDevices(n: Int): String = if (n == 1) "Encrypted for your device" else "Encrypted for your $n devices"
    const val NOT_SET_UP = "This device isn’t set up for encrypted messages yet"
    const val TRY_AGAIN = "Try again"
    const val FULL = "You have 20 devices signed in · Remove one to read encrypted messages here"
    const val GROUP_GUEST = "Guests can’t take part in private groups yet."

    /** The line under a held message (the web's `heldText`); NAME: a person's display name. */
    fun held(h: EncWrite.Hold, name: (String) -> String, group: Boolean = false): String = when (h.reason) {
        HoldReason.NO_DEVICE -> if (h.who != null) "${name(h.who)} isn’t signed in on any device · It will be sent when they are" else COULD_NOT_CHECK
        HoldReason.NOT_LISTED -> NOT_SET_UP
        HoldReason.FULL -> "You have 20 devices signed in · Remove one to send encrypted messages here"
        HoldReason.TOO_MANY -> if (group) "Too many devices in this group to encrypt for everyone." else "Too many devices to encrypt for everyone here"
        HoldReason.MAIL -> "E-mail from the chat waits for this belfry’s update"
        HoldReason.GUEST -> if (group) GROUP_GUEST else "Not sent: this encrypted conversation now includes a guest, and guests can’t read encrypted messages yet"
        else -> if (group) "Not sent: couldn’t check who can read this group" else COULD_NOT_CHECK
    }

    private const val COULD_NOT_CHECK = "Not sent: couldn’t check who can read this conversation"

    // ---- SPEC 19.83: people conversations (wording not final: part 4 / 5 draw the screens) ----
    /** §9: a message whose key is here but that can't be opened yet (the author's lists are coming). */
    const val OPENING = "Opening this message…"
    /** §17.2: an unencrypted message after its conversation's first key (it never acts). */
    const val NOT_ENCRYPTED = "Not encrypted"
    const val NOT_ENCRYPTED_WHY = "This message came without encryption after this conversation was encrypted. It may come from an old app, or not from this person’s own app. Reactions and deletions sent this way don’t count."

    /** §9 in Messages: N messages whose key isn't here yet. */
    fun opening(n: Int): String = if (n == 1) "Opening 1 encrypted message…" else "Opening $n encrypted messages…"

    /** §9 in Messages: N messages with no copy for this device (part 5 adds Ask for them). */
    fun unreadable(n: Int): String = if (n == 1) "1 message can’t be read on this device" else "$n messages can’t be read on this device"

    /** §17.5: in a conversation with others, the people with no device. */
    fun wontGet(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> "${names[0]} won’t get this"
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last() + " won’t get this"
    }

    /** A held message's Try again: every reason but twenty devices (removing one is the way). */
    fun canTryAgain(h: EncWrite.Hold): Boolean = h.reason != HoldReason.FULL
}
