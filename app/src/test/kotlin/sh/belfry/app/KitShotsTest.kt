package sh.belfry.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Test
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.app.ui.kit.ConfirmContent
import sh.belfry.app.ui.kit.ConfirmSheet
import sh.belfry.app.ui.kit.KitGallery
import sh.belfry.app.ui.kit.SheetContent
import sh.belfry.app.ui.kit.SheetGrab
import sh.belfry.app.ui.theme.Belfry

class KitShotsTest : Shots() {
    @Test fun galleryPage() = shot("kit-page") { KitGallery.Page() }
    @Test fun galleryButtons() = shot("kit-buttons") { KitGallery.Buttons() }
    @Test fun galleryLists() = shot("kit-lists") { KitGallery.Lists() }
    @Test fun galleryBits() = shot("kit-bits") { KitGallery.BitsSection() }
    @Test fun galleryIcons() = shot("kit-icons") { KitGallery.IconsSection() }

    /** Sheet bodies rendered directly (no window): always works. */
    @Test
    fun sheetBodies() = shot("kit-sheet-bodies") {
        Column(Modifier.fillMaxSize()) {
            SheetGrab()
            SheetContent(title = "Message") { MenuRows() }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.padding(16.dp)) {
                ConfirmContent(
                    title = "Delete this message?", confirmLabel = "Delete", onConfirm = {}, onDismiss = {},
                    message = "It is removed for everyone. This cannot be undone.",
                    preview = { Text("See you all at the harvest dinner on Friday 🎉", fontSize = 15.sp, color = Belfry.colors.text) },
                )
            }
        }
    }

    /** The real ModalBottomSheet, in its own window, over a page. */
    @Test
    fun menuSheet() = screenShot("kit-sheet-menu") {
        KitGallery.Page()
        BelfrySheet(onDismiss = {}, title = "Message") { MenuRows() }
    }

    @Test
    fun confirmSheet() = screenShot("kit-sheet-confirm") {
        KitGallery.Page()
        ConfirmSheet(
            title = "Delete this message?", confirmLabel = "Delete", onConfirm = {}, onDismiss = {},
            message = "It is removed for everyone. This cannot be undone.",
            preview = { Text("See you all at the harvest dinner on Friday 🎉", fontSize = 15.sp, color = Belfry.colors.text) },
        )
    }
}

@androidx.compose.runtime.Composable
private fun MenuRows() {
    ActionGroup {
        ActionRow("Reply in a thread", onClick = {}, icon = BelfryIcons.Reply)
        ActionRow("Edit", onClick = {}, icon = BelfryIcons.Edit)
        ActionRow("Copy text", onClick = {}, icon = BelfryIcons.Copy)
    }
    Spacer(Modifier.height(12.dp))
    ActionGroup {
        ActionRow("Delete", onClick = {}, icon = BelfryIcons.Trash, danger = true)
    }
}
