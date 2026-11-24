package sh.belfry.core.model

import java.text.Normalizer

/**
 * What a hashtag / channel slug field lets in, as the person types and on paste (Xavier via the
 * lead, 2026-11-24, SPEC 19.89): lower case; a run of spaces becomes one "-"; accents folded
 * (NFKD, then ASCII only); only `a–z 0–9 -` kept, so punctuation and emoji are simply not
 * inserted; never a leading "-", never "--"; 30 characters at most. A trailing "-" stays while
 * typing (the next word follows it) and goes when the field is left or the form is sent ([done]).
 * Every field where a hashtag or a channel's slug is typed uses this, and the availability check
 * runs on the cleaned value.
 */
object HashtagInput {
    const val MAX = 30
    private val MARKS = Regex("\\p{M}+")
    private val SPACES = Regex("\\s+")
    private val OUTSIDE = Regex("[^a-z0-9-]")
    private val DASHES = Regex("-{2,}")

    /** The field's value after an edit (typed or pasted RAW): see the rules above. */
    fun typed(raw: String): String {
        val folded = Normalizer.normalize(raw, Normalizer.Form.NFKD).replace(MARKS, "")
        return folded.lowercase()
            .replace(SPACES, "-")
            .replace(OUTSIDE, "")
            .replace(DASHES, "-")
            .trimStart('-')
            .take(MAX)
    }

    /** The value once the field is left or the form is sent: [typed], without a trailing "-". */
    fun done(raw: String): String = typed(raw).trimEnd('-')
}
