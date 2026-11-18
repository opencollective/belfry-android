package sh.belfry.app

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsNotEnabled
import org.junit.Test
import sh.belfry.app.ui.signin.ApplyContent
import sh.belfry.app.ui.signin.FindContent
import sh.belfry.app.ui.signin.SentContent
import sh.belfry.app.ui.signin.WelcomeContent
import sh.belfry.core.net.ApplyForm
import sh.belfry.core.net.Community
import sh.belfry.core.net.PublicAgreement

/** No link (boards onboarding/no-link-belfry, no-link, apply-form, apply-sent). */
class NoLinkShotsTest : Shots() {
    private val riverside = Community.defaults("belfry.example").copy(name = "Riverside Commons")
    private val charter = listOf(
        PublicAgreement("a".repeat(64), "We look after each other", "Kind words, good faith.", 1, 0, 0),
        PublicAgreement("b".repeat(64), "Shared things come back", "Return tools within a week.", 2, 0, 0),
        PublicAgreement("c".repeat(64), "Quiet hours after 22:00", "In the hall and the garden.", 3, 0, 0),
    )

    @Test fun find() = shot("nolink-find") { FindContent("", {}, null, {}, {}, {}) }

    @Test fun welcome() = shot("nolink-welcome") { WelcomeContent(riverside, {}, {}, {}, {}) }

    @Test fun applyForm() {
        shot("nolink-apply") {
            ApplyContent("lina@example.org", {}, "I live on Mill Street and would love to help in the garden on Sundays.", {},
                charter, listOf(charter[0].id, charter[1].id), {}, null, null, {}, {}, {})
        }
        compose.onNodeWithTag("apply-send").assertIsNotEnabled()
    }

    @Test fun applyRefused() = shot("nolink-apply-error") {
        ApplyContent("lina@example.org", {}, "Garden on Sundays.", {}, charter, charter.map { it.id }, {}, ApplyForm.NETWORK, null, {}, {}, {})
    }

    @Test fun sent() = shot("nolink-sent") { SentContent {} }
}
