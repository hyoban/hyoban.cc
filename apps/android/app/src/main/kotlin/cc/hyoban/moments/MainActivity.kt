package cc.hyoban.moments

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cc.hyoban.moments.ui.MomentsApp
import cc.hyoban.moments.ui.theme.MomentsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as MomentsApplication).container
        setContent {
            MomentsTheme(container.settings) {
                MomentsApp(container = container)
            }
        }
        // Compose paints the whole window, so the window's own background would only be
        // painted over on every frame.
        window.setBackgroundDrawable(null)
    }
}
