package sh.belfry.core.nostr

import sh.belfry.core.crypto.B64
import sh.belfry.core.crypto.sha256Hex

/** HTTP requests signed with Nostr keys: NIP-98 (the belfry's API) and Blossom BUD-02 uploads. */
object Nip98 {
    /** Kind 27235 for one request. The `payload` tag is there only when there is a body. */
    fun template(url: String, method: String, body: String? = null, now: Long = unixNow()): EventTemplate {
        val tags = ArrayList<Tag>()
        tags.add(listOf("u", url))
        tags.add(listOf("method", method.uppercase()))
        if (!body.isNullOrEmpty()) tags.add(listOf("payload", sha256Hex(body)))
        return EventTemplate(27235, "", tags, now)
    }

    /** The value of the `Authorization` header for a signed event. */
    fun authorization(signed: Event): String = "Nostr " + B64.encode(signed.toJsonString().toByteArray(Charsets.UTF_8))

    suspend fun header(signer: Signer, url: String, method: String, body: String? = null): String =
        authorization(signer.sign(template(url, method, body)))

    fun headerNow(signer: LocalSigner, url: String, method: String, body: String? = null): String =
        authorization(signer.signNow(template(url, method, body)))

    /** Blossom upload authorization (SPEC 5): kind 24242, valid 10 minutes. */
    fun blossomUpload(name: String, sha256: String, now: Long = unixNow()): EventTemplate = EventTemplate(
        24242, "Upload $name",
        listOf(listOf("t", "upload"), listOf("x", sha256), listOf("expiration", (now + 600).toString())),
        now,
    )

    /** NIP-42: the event that answers a relay's AUTH challenge. */
    fun relayAuth(relay: String, challenge: String, now: Long = unixNow()): EventTemplate =
        EventTemplate(22242, "", listOf(listOf("relay", relay), listOf("challenge", challenge)), now)
}
