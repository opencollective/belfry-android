package sh.belfry.app.ui.people

import androidx.compose.runtime.Composable
import sh.belfry.core.engine.Session

/**
 * Shown above the composer to a guest who joined with a name only, once they did something:
 * "Save your account" (e-mail, then a 6-digit code: `session.guestSave`, `session.guestVerify`).
 * `session.guest` says whether they are a guest and whether the account is saved.
 * Seam: renders nothing until the people screens are built.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SaveAccountBar(session: Session) {
}
