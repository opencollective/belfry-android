package sh.belfry.core.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import sh.belfry.core.model.World
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.typing.TypingPlace
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** No typing events in a sealed conversation (the lead, for the sealed release 0.7.6). */
class SealedTypingTest {
    private val me = LocalSigner.random()
    private val bob = LocalSigner.random().pubkey
    private val carol = LocalSigner.random().pubkey
    private val sealedConvs = setOf("n:$bob")

    private val world = World(emptyList(), ctx = sh.belfry.core.model.WorldContext(me = me.pubkey))

    private val typing = SessionTyping(
        CoroutineScope(Dispatchers.Unconfined), me.pubkey, me, { 1_795_000_000L }, { 1_795_000_000.0 },
        { null }, { world }, { true }, { c -> c in sealedConvs },
    )

    @Test fun sealedConversationSendsNone() {
        assertFalse(typing.allowed(TypingPlace.dm("n:$bob")))
    }

    @Test fun otherConversationsStillDo() {
        assertTrue(typing.allowed(TypingPlace.dm("n:$carol")))
        assertTrue(typing.allowed(TypingPlace.channel("general")))
    }
}
