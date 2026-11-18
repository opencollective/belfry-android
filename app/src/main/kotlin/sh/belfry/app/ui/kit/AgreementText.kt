package sh.belfry.app.ui.kit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.belfry.app.ui.theme.Belfry

/**
 * A charter agreement as every list shows it (Xavier, 2026-11-18; boards onboarding/welcome-charter,
 * guest-apply, apply-form, home/home): its title, then at most TWO lines of its explanation; when
 * that is cut, "more" shows the whole of it.
 */
@Composable
fun AgreementText(title: String, about: String, modifier: Modifier = Modifier, titleSize: Int = 16, aboutSize: Int = 14) {
    val c = Belfry.colors
    var open by remember(about) { mutableStateOf(false) }
    var cut by remember(about) { mutableStateOf(false) }
    Column(modifier) {
        Text(title, fontSize = titleSize.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        if (about.isNotEmpty()) {
            Text(
                about, fontSize = aboutSize.sp, lineHeight = (aboutSize + 5).sp, color = c.muted,
                maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!open) cut = it.hasVisualOverflow },
                modifier = Modifier.padding(top = 2.dp),
            )
            if (cut && !open) {
                Text(
                    "more", fontSize = aboutSize.sp, fontWeight = FontWeight.SemiBold, color = c.text, textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Show the whole agreement") { open = true }.padding(vertical = 2.dp).testTag("agreement-more"),
                )
            }
        }
    }
}
