package sh.belfry.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.junit.Test
import sh.belfry.app.ui.people.ConversationScreen
import sh.belfry.app.ui.people.MeScreen
import sh.belfry.app.ui.people.MembersScreen
import sh.belfry.app.ui.people.MessagesScreen
import sh.belfry.app.ui.people.NewConversationScreen
import sh.belfry.app.ui.people.ProfileScreen
import sh.belfry.app.ui.people.SaveAccountFlow
import sh.belfry.app.ui.people.SessionsContent
import sh.belfry.app.ui.people.SessionsScreen
import sh.belfry.core.engine.Demo
import sh.belfry.core.mail.MailThreads
import sh.belfry.core.net.SessionInfo

/** Profiles, Me, sessions, members, messages and e-mail in demo mode. */
class PeopleShotsTest : DemoShots() {
    @Test fun profileOther() = demoShot("profile-other") { ProfileScreen(Demo.maya.pubkey) }

    @Test fun profileMine() = demoShot("profile-me") { ProfileScreen(session.me) }

    @Test fun profileEdit() = demoShot("profile-edit") { ProfileScreen(session.me, startEditing = true) }

    /** A guest who has not saved their account: chips, and the admin switch off with the reason. */
    @Test fun profileGuest() = demoShot("profile-guest") { ProfileScreen(Demo.marcel.pubkey) }

    @Test fun profileAgent() = demoShot("profile-agent") { ProfileScreen(Demo.scout.pubkey) }

    /** Promoted to member: no longer called a guest. */
    @Test fun profilePromoted() = demoShot("profile-promoted") { ProfileScreen(Demo.nora.pubkey) }

    @Test fun me() = demoShot("me") { MeScreen() }

    @Test fun sessionsDemo() = demoShot("sessions-demo") { SessionsScreen() }

    @Test fun sessions() = demoShot("sessions") {
        val rows = listOf(
            SessionInfo("Belfry for Android", "Pixel 8", "sh.belfry.app://connect", "a".repeat(64), "2026-11-02T09:12:00Z", "2026-11-10T08:01:00Z"),
            SessionInfo("Chat", "Firefox on Linux", "https://chat.commons.example", "b".repeat(64), "2026-10-14T17:40:00Z", "2026-11-09T21:15:00Z"),
            SessionInfo("Chat", "Safari on iPhone", "https://chat.commons.example", "c".repeat(64), "2026-09-01T07:30:00Z", null),
        )
        SessionsContent(session.me, false, Demo.NAME, rows, null, isThis = { it.bunkerPubkey.startsWith("a") }, onReload = {}, onEnd = {})
    }

    @Test fun members() = demoShot("members") { MembersScreen() }

    @Test fun messages() = demoShot("messages") { MessagesScreen() }

    @Test fun newConversation() = demoShot("new-conversation") { NewConversationScreen() }

    @Test fun conversation() = demoShot("conversation") { ConversationScreen("n:" + Demo.maya.pubkey) }

    @Test fun conversationGroup() = demoShot("conversation-group") {
        ConversationScreen(MailThreads.nostrConv(Demo.tomas.pubkey, listOf(Demo.lena.pubkey, session.me), session.me))
    }

    @Test fun conversationMail() = demoShot("conversation-mail") {
        ConversationScreen(MailThreads.mailThreadOf("ana@example.org", "Visiting next week"))
    }

    @Test fun conversationMailUnverified() = demoShot("conversation-mail-unverified") {
        ConversationScreen(MailThreads.mailThreadOf("permits@cityhall.example", "Street party permit"))
    }

    @Test fun conversationNewMail() = demoShot("conversation-new-mail") { ConversationScreen("m:ana@example.org") }

    /** The guest's "save your account" bar, step by step. */
    @Test fun saveAccount() = shot("save-account") {
        Column(Modifier.padding(top = 40.dp)) {
            for (step in listOf("line", "email", "code", "done")) {
                SaveAccountFlow(step, if (step == "line") "" else "marcel@example.org", {}, {}, {}, {}, onHide = {})
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
