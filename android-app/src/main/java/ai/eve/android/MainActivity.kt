package ai.eve.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.eve.android.ui.EveScreen
import ai.eve.android.ui.EveTheme
import ai.eve.android.ui.EveViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EveTheme {
                val vm: EveViewModel = viewModel()
                EveScreen(vm)
            }
        }
    }
}
