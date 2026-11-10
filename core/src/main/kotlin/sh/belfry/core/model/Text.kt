package sh.belfry.core.model

import sh.belfry.core.crypto.Bech32
import sh.belfry.core.nostr.Tag
import java.text.Normalizer
import java.util.Locale

/** Hashtags (NIP-24 `t` tags, lowercase). Same regex as the web chat's `HASHTAG`. */
object Topics {
    /** Group 1 = the character before the `#` (or empty), group 2 = the topic. */
    val HASHTAG = Regex("(^|[^\\p{L}\\p{N}_&/])#([\\p{L}\\p{N}_][\\p{L}\\p{N}_-]{0,48})")

    const val ANNOUNCE = "announcement"
    /** NIP-32 namespace of a channel's pinned topics. */
    const val NS = "#t"

    fun norm(t: String): String = t.lowercase(Locale.ROOT).removePrefix("#")

    /** The hashtags of a text: lowercased, deduplicated, in order of appearance. */
    fun hashtags(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (m in HASHTAG.findAll(text)) out.add(norm(m.groupValues[2]))
        return out.toList()
    }
}

/** Mentions (NIP-27): `nostr:npub1…` in the text, `p` tags on the event; the composer's @ picker. */
object Mentions {
    val NPUB_IN_TEXT = Regex("nostr:(npub1[02-9ac-hj-np-z]{58})")
    /** Any `nostr:npub1…` run, valid or not (for snippets: shown as "@…"). */
    val NPUB_LOOSE = Regex("nostr:npub1[02-9ac-hj-np-z]+")
    // JS `$` without the m flag is the very end of the text: \z here. JS \s covers Unicode spaces.
    private val PICKER = Regex("(^|[\\s\\u00a0\\ufeff\\p{Z}])@([\\p{L}\\p{N}_.-]{0,30})\\z")

    /** `p` tags for the people a text mentions: deduplicated, first appearance first. */
    fun tags(text: String, skip: Collection<String> = emptyList()): List<Tag> {
        val keys = LinkedHashSet<String>()
        for (m in NPUB_IN_TEXT.findAll(text)) {
            val hex = Bech32.npubToHex(m.groupValues[1]) ?: continue
            if (hex !in skip) keys.add(hex)
        }
        return keys.map { listOf("p", it) }
    }

    /** The pubkeys (hex) a text mentions, in order. */
    fun pubkeys(text: String): List<String> = tags(text).map { it[1] }

    /** The query of an open @ picker, given the text before the caret; null when none is open. */
    fun query(beforeCaret: String): String? = PICKER.find(beforeCaret)?.groupValues?.get(2)

    /** People for the picker: someone whose name has a word starting with the query (at most 6). */
    fun candidates(query: String, people: List<Pair<String, String>>, me: String, limit: Int = 6): List<Pair<String, String>> {
        val q = query.lowercase(Locale.ROOT)
        return people.filter { (pk, name) ->
            pk != me && name.isNotEmpty() && name.lowercase(Locale.ROOT).split(Regex("\\s+")).any { it.startsWith(q) }
        }.take(limit)
    }

    /**
     * A pick in the composer: replaces the "@query" before the caret by "@Name ".
     * Returns the new text and the new caret position.
     */
    fun pick(text: String, caret: Int, query: String, name: String): Pair<String, Int> {
        val start = (caret - query.length - 1).coerceAtLeast(0)
        val next = text.substring(0, start) + "@" + name + " " + text.substring(caret.coerceAtMost(text.length))
        return next to (start + name.length + 2)
    }

    /** The composer's text as sent: each picked "@Name" (longest names first) → `nostr:npub…`. */
    fun substitute(text: String, picked: List<Pair<String, String>>): String {
        var out = text
        for ((name, pubkey) in picked.sortedByDescending { it.first.length }) {
            val npub = try { Bech32.npub(pubkey) } catch (e: IllegalArgumentException) { continue }
            out = out.replace("@$name", "nostr:$npub")
        }
        return out
    }

    /** For editing: `nostr:npub…` shown again as "@Name"; returns the text and the picks to send back. */
    fun unsubstitute(text: String, nameOf: (String) -> String): Pair<String, List<Pair<String, String>>> {
        val picked = ArrayList<Pair<String, String>>()
        val out = NPUB_IN_TEXT.replace(text) { m ->
            val hex = Bech32.npubToHex(m.groupValues[1])
            val name = hex?.let(nameOf).orEmpty()
            if (hex == null || name.isEmpty()) m.value else {
                if (picked.none { it.first == name }) picked.add(name to hex)
                "@$name"
            }
        }
        return out to picked
    }

    /** A snippet without raw npubs (thread lines, delete confirmation): each mention becomes "@…". */
    fun hide(text: String): String = NPUB_LOOSE.replace(text, "@…")
}

/** Folding for search: accents and case ignored (JS: normalize('NFD'), strip \p{M}, lowercase). */
fun fold(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT)

/** JS `String.prototype.slice(0, n)` on UTF-16 units, never splitting a surrogate pair badly for display. */
internal fun String.sliceUnits(n: Int): String = if (length <= n) this else substring(0, n)

/** JS `trim()` (also strips Unicode spaces and line terminators). */
internal fun String.jsTrim(): String = trim { it.isWhitespace() || it == '﻿' || it == ' ' }
