package sh.belfry.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** The hashtag field's filter (SPEC 19.89; Xavier via the lead, 2026-11-24). */
class HashtagInputTest {
    @Test fun asTypedAndOnPaste() {
        for ((raw, typed, done) in listOf(
            Triple("Tool Library", "tool-library", "tool-library"),
            Triple("Café  Réparé!", "cafe-repare", "cafe-repare"),
            Triple("  -a--b- ", "a-b-", "a-b"),
            Triple("bikes 🚲 and more", "bikes-and-more", "bikes-and-more"),
            Triple("🚲", "", ""),
            Triple("a".repeat(40), "a".repeat(30), "a".repeat(30)),
            Triple("tool-", "tool-", "tool"),
            Triple("Hello, world. (yes)", "hello-world-yes", "hello-world-yes"),
            Triple("#general", "general", "general"),
            Triple("Ærø straße", "r-strae", "r-strae"),
            Triple("tab\tand\nnew line", "tab-and-new-line", "tab-and-new-line"),
            Triple("", "", ""),
        )) {
            assertEquals(typed, HashtagInput.typed(raw), raw)
            assertEquals(done, HashtagInput.done(raw), raw)
        }
    }

    /** Typing letter by letter gives the same as pasting the whole. */
    @Test fun letterByLetter() {
        var v = ""
        for (ch in "Café  Réparé!") v = HashtagInput.typed(v + ch)
        assertEquals("cafe-repare", v)
        v = ""
        for (ch in "  -a--b- ") v = HashtagInput.typed(v + ch)
        assertEquals("a-b-", v)
        assertEquals("a-b", HashtagInput.done(v))
    }

    /** What the field lets in is what the belfry checks: the card's slug of a done value is the value itself. */
    @Test fun theCheckRunsOnTheCleanedValue() {
        for (raw in listOf("Tool Library", "Café  Réparé!", "  -a--b- ", "x".repeat(45))) {
            val d = HashtagInput.done(raw)
            assertEquals(d, Cards.hashtagSlug(d))
        }
    }
}
