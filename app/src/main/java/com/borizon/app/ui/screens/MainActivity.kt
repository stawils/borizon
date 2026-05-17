package com.borizon.app.ui.screens

import android.content.Intent
import android.app.ComponentCaller
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.activity.viewModels
import com.borizon.app.di.AppLifecycleProvider
import com.borizon.app.ui.components.AnimatedSplashScreen
import com.borizon.app.ui.navigation.BorizonNavHost
import com.borizon.app.ui.theme.BorizonTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var lifecycleProvider: AppLifecycleProvider

    private val viewModel: BorizonViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install invisible system splash — dismissed as soon as Compose renders
        val splashScreen = installSplashScreen()

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val impl = lifecycleProvider as com.borizon.app.di.AppLifecycleProviderImpl
        if (!impl.isRegistered) {
            lifecycle.addObserver(impl)
            impl.markRegistered()
        }

        // Dismiss the invisible system splash instantly — animated Compose splash
        // handles the visual transition
        var contentReady = false
        splashScreen.setKeepOnScreenCondition { !contentReady }

        setContent {
            BorizonTheme {
                val prefsReady by viewModel.prefsLoaded.collectAsState()
                var splashFinished by remember { mutableStateOf(false) }

                // Content is ready to show — dismiss system splash after first frame
                LaunchedEffect(Unit) { contentReady = true }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BorizonNavHost(viewModel = viewModel)
                }

                // Animated Compose splash — covers content until animation completes
                if (!splashFinished) {
                    AnimatedSplashScreen(onFinished = {
                        splashFinished = true
                    })
                }

                // Dismiss splash once prefs are ready (for first-launch routing)
                LaunchedEffect(prefsReady) {
                    // AnimatedSplashScreen has its own timing; this ensures
                    // the nav graph underneath uses real preference values.
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent, caller: ComponentCaller) {
        super.onNewIntent(intent, caller)
        handleIngestIntent(intent)
    }

    private fun handleIngestIntent(intent: Intent?) {
        val imageUriStr = intent?.getStringExtra(IngestActivity.EXTRA_INGEST_IMAGE_URI)
        if (!imageUriStr.isNullOrBlank()) {
            viewModel.handleIngestedImage(android.net.Uri.parse(imageUriStr))
            return
        }
        val text = intent?.getStringExtra(IngestActivity.EXTRA_INGEST_TEXT)
        val source = intent?.getStringExtra(IngestActivity.EXTRA_INGEST_SOURCE) ?: "Shared content"
        if (!text.isNullOrBlank()) {
            viewModel.handleIngestedContent(text, source)
        }
    }
}
