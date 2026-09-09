package dev.nphil.blueshark

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nphil.blueshark.guide.GuideController
import dev.nphil.blueshark.ui.shell.AppShell
import dev.nphil.blueshark.ui.theme.BlueSharkTheme
import dev.nphil.blueshark.ui.theme.ThemeSettings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as BlueSharkApp).container
        // A cold start can be the Finish press: the overlay may have brought a dead process back.
        handleFinishLearning(intent, container)
        setContent {
            val settings by container.themes.settings.collectAsStateWithLifecycle(ThemeSettings())
            BlueSharkTheme(settings) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppShell(container, settings)
                }
            }
        }
    }

    /**
     * The launch mode is `singleTask`, so the usual case is not a cold start at all: the activity
     * is already there and the overlay's intent arrives here instead.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleFinishLearning(intent, (application as BlueSharkApp).container)
    }

    /**
     * The overlay's Finish button asks BlueShark to close the learning session and collect.
     *
     * The extra is consumed here rather than read from the composition: an intent is delivered
     * once, and the same one is still attached to the activity across a configuration change, so
     * a composable reading it would fire the collection again on every rotation.
     * [dev.nphil.blueshark.ui.project.LearnSessionCoordinator] resolves which project the session
     * belongs to - from memory, or from the only unfinished learning record on disk when this
     * process was started by the intent itself - and the shell routes to it.
     */
    private fun handleFinishLearning(intent: Intent?, container: AppContainer) {
        if (intent?.getBooleanExtra(GuideController.EXTRA_FINISH_LEARNING, false) != true) return
        intent.removeExtra(GuideController.EXTRA_FINISH_LEARNING)
        container.learning.requestFinish()
    }
}
