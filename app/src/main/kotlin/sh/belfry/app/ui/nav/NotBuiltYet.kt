package sh.belfry.app.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import sh.belfry.app.ui.kit.BackButton
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.EmptyState
import sh.belfry.app.ui.kit.Hairline
import sh.belfry.app.ui.kit.TopBar
import sh.belfry.app.ui.theme.Belfry

/** A route whose screen is not written yet. Replace the call in the owner's routes file. */
@Composable
fun NotBuiltYet(title: String) {
    val nav = LocalNav.current
    Column(Modifier.fillMaxSize().background(Belfry.colors.bg)) {
        TopBar(title, navigation = { BackButton { nav.back() } })
        Hairline()
        EmptyState("Not in the Android app yet", text = "This screen exists in the web chat and is still to be built here.", icon = BelfryIcons.Clock)
    }
}
