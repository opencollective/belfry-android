package sh.belfry.core.net

import kotlinx.serialization.json.JsonObject
import sh.belfry.core.nostr.parseJsonOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `GET /groups/<group>/card` and `POST /cards/<id>/retire` (SPEC 19.76; docs/cards.md §2.5, §2.6). */
class GroupCardTest {
    private fun j(s: String) = parseJsonOrNull(s) as JsonObject
    private val lou = "b".repeat(64)

    @Test fun theAnswer() {
        assertEquals(
            GroupCard("K7Q2MX", false, "Lou", lou, 8),
            GroupCards.parse(j("""{"id":"K7Q2MX","kind":"group","started_by":{"name":" Lou ","pubkey":"$lou"},"joined_by_scan":8}""")),
        )
        // a topic; lower case reads as upper case; a key that is not one is dropped; no count: 0
        assertEquals(GroupCard("T9PC4H", true, "Dev", null, 0), GroupCards.parse(j("""{"id":"t9pc4h","kind":"topic","started_by":{"name":"Dev","pubkey":"xyz"}}""")))
        assertEquals(GroupCard("K7Q2MX", false, "", null, 0), GroupCards.parse(j("""{"id":"K7Q2MX","joined_by_scan":-3}""")))
        // not a card's id (O, 0, I, 1, L are never printed; six characters)
        for (bad in listOf("K7Q2M", "K7Q2MXX", "K7Q0MX", "K7QIMX", "../../x", "")) assertNull(GroupCards.parse(j("""{"id":"$bad"}""")), bad)
        assertNull(GroupCards.parse(j("""{"error":"not_found"}""")))
        assertNull(GroupCards.parse(null))
    }

    @Test fun wordsAndErrors() {
        assertEquals("1 person joined by scanning it", GroupCards.joinedWords(1))
        assertEquals("0 people joined by scanning it", GroupCards.joinedWords(0))
        assertEquals("8 people joined by scanning it", GroupCards.joinedWords(8))
        // our words for a code, never the server's message
        val e = GroupCards.error(403, j("""{"error":"not_allowed","message":"Forbidden for you, pal"}"""))
        assertEquals("not_allowed", e.code)
        assertEquals(GroupCards.ERRORS["not_allowed"], e.message)
        assertEquals("not_found", GroupCards.error(404, null).code)
        assertEquals(GroupCards.FALLBACK, GroupCards.error(500, j("""{"error":"boom","message":"stack trace"}""")).message)
        assertEquals(GroupCards.FALLBACK, GroupCards.error(503, j("""{"error":"relay","message":"x"}""")).message)
    }

    @Test fun theCardsAddress() {
        assertEquals("https://chat.belfry.example/g/K7Q2MX", GroupCards.address("https://chat.belfry.example/", "K7Q2MX"))
        assertEquals("https://chat.belfry.example/g/K7Q2MX", GroupCards.address("https://chat.belfry.example", "K7Q2MX"))
        assertEquals("https://belfry.example/g/K7Q2MX", GroupCards.address("https://belfry.example/chat/", "K7Q2MX"))
    }
}
