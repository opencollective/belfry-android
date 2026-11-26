package sh.belfry.core.enc

/**
 * SPEC 19.94 (the lead, 2026-11-26): who reads a private group decides who gets its key, so a group's metadata counts
 * for a write only when it is TRUSTED: the groups relay's own key (its NIP-11 `self`) is known and only 39000–39003
 * signed by it were read. Untrusted, the group counts as unknown: [EncWrites.group] holds it on format 3
 * (GROUP_UNKNOWN), a group ever encrypted here stays V3 (and its member list still needs a relay-signed 39002).
 * Never a plain write because of this.
 */
object GroupTrust {
    fun write(f: EncWrites.Facts, ever: Boolean, trusted: Boolean, privateMeta: Boolean, known: Boolean, clearKind: Boolean): EncWrite =
        EncWrites.group(f, ever, trusted && privateMeta, trusted && known, clearKind)
}
