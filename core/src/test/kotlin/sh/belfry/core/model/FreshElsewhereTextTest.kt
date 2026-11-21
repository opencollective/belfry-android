package sh.belfry.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** canvas v122 / v124 messages/encryption-fresh-elsewhere: the title with and without a device name. */
class FreshElsewhereTextTest {
    @Test fun named() = assertEquals("Sealed conversations were started fresh on “Firefox on Linux”", SealedText.freshElsewhereTitle("Firefox on Linux"))

    @Test fun unnamed() = assertEquals("Sealed conversations were started fresh on a new device", SealedText.freshElsewhereTitle(null))
}
