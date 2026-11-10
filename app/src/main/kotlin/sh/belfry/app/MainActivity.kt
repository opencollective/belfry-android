package sh.belfry.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import sh.belfry.app.ui.kit.KitGallery
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.app.ui.theme.BelfryTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BelfryTheme {
                // Until the screens land: debug builds show the component gallery.
                if (BuildConfig.DEBUG) {
                    KitGallery.Page()
                } else {
                    Box(Modifier.fillMaxSize().background(Belfry.colors.bg), contentAlignment = Alignment.Center) {
                        Text("Belfry", color = Belfry.colors.text)
                    }
                }
            }
        }
    }
}
