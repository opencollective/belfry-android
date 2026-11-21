package sh.belfry.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test
import sh.belfry.app.ui.kit.BelfrySheet
import sh.belfry.app.ui.kit.KitGallery
import sh.belfry.app.ui.theme.BelfryTheme
import sh.belfry.app.update.UpdateAskContent
import sh.belfry.app.update.UpdateText
import sh.belfry.app.update.Updater

/** Update when Android's "Install unknown apps" is off (OSV, 2026-11-21): one line on why, then the setting or belfry.sh. */
class UpdateShotsTest : Shots() {
    @Test
    fun askBodies() = shot("update-ask-bodies") {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            UpdateAskContent(again = false, onSetting = {}, onWeb = {})
            Spacer(Modifier.height(32.dp))
            UpdateAskContent(again = true, onSetting = {}, onWeb = {})
        }
    }

    @Test
    fun askSheet() = screenShot("update-ask-sheet") {
        KitGallery.Page()
        BelfrySheet(onDismiss = {}) { UpdateAskContent(again = false, onSetting = {}, onWeb = {}) }
    }

    @Test
    fun theRowsDoWhatTheySay() {
        var setting = 0; var web = 0
        compose.setContent { BelfryTheme { UpdateAskContent(again = false, onSetting = { setting++ }, onWeb = { web++ }) } }
        compose.onNodeWithText(UpdateText.ASK_LINE).assertExists()
        compose.onNodeWithTag("update-ask-setting").performClick()
        compose.onNodeWithTag("update-ask-web").performClick()
        compose.waitForIdle()
        assertEquals(1, setting); assertEquals(1, web)
    }

    @Test
    fun aFailedInstallOffersBelfrySh() {
        assertEquals("belfry.sh", UpdateText.button(Updater.State.Failed("x", web = true)))
        assertEquals("Update", UpdateText.button(Updater.State.Failed("x")))
        assertEquals("Update", UpdateText.button(Updater.State.Idle))
    }
}
