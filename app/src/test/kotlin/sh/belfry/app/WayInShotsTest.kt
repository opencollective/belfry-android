package sh.belfry.app

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import org.junit.Test
import sh.belfry.app.ui.signin.UsernameField
import sh.belfry.app.ui.signin.WayInCode
import sh.belfry.app.ui.signin.WayInDeclined
import sh.belfry.app.ui.signin.WayInEmail
import sh.belfry.app.ui.signin.WayInForm
import sh.belfry.app.ui.signin.WayInPending
import sh.belfry.app.ui.signin.WayInUnknown
import sh.belfry.core.net.Community
import sh.belfry.core.net.PublicAgreement

/** The web's way in (canvas onboarding/login … apply-declined, 2026-11-19), screen by screen. */
class WayInShotsTest : Shots() {
    private val riverside = Community.defaults("belfry.example").copy(name = "Riverside Commons")
    private val charter = listOf(
        PublicAgreement("a".repeat(64), "We look after each other", "Kind words, good faith.", 1, 0, 0),
        PublicAgreement("b".repeat(64), "Shared things come back", "Return tools within a week.", 2, 0, 0),
    )

    @Test fun login() = shot("wayin-login") { WayInEmail("Log in", "We send a 6-digit code to your e-mail. No password.", null, {}, {}) }
    @Test fun code() = shot("wayin-code") { WayInCode("lina@example.org", null, { false }, {}, {}) }
    @Test fun codeWrong() = shot("wayin-code-wrong") { WayInCode("lina@example.org", "That code is not right. 3 tries left.", { false }, {}, {}) }
    @Test fun unknown() = shot("wayin-unknown") { WayInUnknown(riverside, "lina@example.org", {}, {}) }
    @Test fun form() = shot("wayin-apply-name") { WayInForm(riverside, "lina@example.org", null, { charter }, null, {}) { _, _, _, _ -> null } }
    @Test fun usernameTaken() = shot("wayin-username-taken") {
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(16.dp)) {
            UsernameField("lina", {}, "belfry.example", "Taken. Try lina-moreau or lina-2", listOf("lina-moreau", "lina-2"), {})
        }
    }
    @Test fun pending() = shot("wayin-pending") {
        WayInPending(riverside, mapOf("name" to "Lina Moreau", "username" to "lina", "email" to "lina.moreau@example.org", "motivation" to "I run a repair café in Ghent."))
    }
    @Test fun declined() = shot("wayin-declined") { WayInDeclined(riverside, {}) }
}

