package com.borizon.app.ui.screens

import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.borizon.app.R
import com.borizon.app.ui.components.BorizonColors
import com.borizon.app.ui.components.BorizonLogoCanvas

/**
 * Mandatory biometric gate using the system BiometricPrompt dialog.
 *
 * Shows the Borizon logo and branding behind the system auth bottom-sheet.
 * The user CANNOT proceed until authentication succeeds.
 */
@Composable
fun BiometricScreen(
    onAuthenticated: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    var errorMessage by remember { mutableStateOf<String?>(null) }

    val currentOnAuthenticated by rememberUpdatedState(onAuthenticated)

    val biometricTitle = stringResource(R.string.biometric_title)
    val biometricSubtitle = stringResource(R.string.biometric_subtitle)
    val notRecognized = stringResource(R.string.biometric_not_recognized)

    val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    } else {
        BIOMETRIC_WEAK
    }

    val promptInfo = remember {
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(biometricTitle)
            .setSubtitle(biometricSubtitle)
            .setAllowedAuthenticators(authenticators)
            .build()
    }

    val promptCallback = remember {
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                Handler(Looper.getMainLooper()).post {
                    errorMessage = null
                    currentOnAuthenticated()
                }
            }

            override fun onAuthenticationFailed() {
                Handler(Looper.getMainLooper()).post {
                    errorMessage = notRecognized
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Handler(Looper.getMainLooper()).post {
                    errorMessage = errString.toString()
                }
            }
        }
    }

    val biometricPrompt = remember(activity) {
        activity?.let { BiometricPrompt(it, promptCallback) }
    }

    // Trigger the system biometric prompt immediately
    LaunchedEffect(Unit) {
        biometricPrompt?.authenticate(promptInfo)
    }

    // Fade-in for logo + text (matches splash screen feel)
    val logoAlpha = remember { Animatable(0f) }
    val titleAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        logoAlpha.animateTo(1f, tween(400, easing = FastOutSlowInEasing))
        titleAlpha.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Logo canvas with glow — same as splash screen
        BorizonLogoCanvas(
            alpha = logoAlpha.value,
            logoVerticalBias = 0.32f,
        )

        // Text below the logo
        val density = LocalDensity.current
        val wPx = with(density) {
            LocalConfiguration.current.screenWidthDp.dp.toPx()
        }
        val hPx = with(density) {
            LocalConfiguration.current.screenHeightDp.dp.toPx()
        }
        val logoSizePx = minOf(wPx, hPx) * 0.40f
        val textTopPadding = with(density) { (hPx * 0.32f + logoSizePx * 0.55f).toDp() }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = textTopPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            // App name
            Text(
                text = stringResource(R.string.app_name),
                style = TextStyle(
                    color = BorizonColors.TEXT.copy(alpha = titleAlpha.value),
                    fontSize = (wPx / density.density * 0.10).sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.01).sp,
                    textAlign = TextAlign.Center
                )
            )

            // Tagline
            Text(
                text = stringResource(R.string.biometric_app_tagline),
                modifier = Modifier.padding(top = 10.dp),
                style = TextStyle(
                    color = BorizonColors.TEXT.copy(alpha = titleAlpha.value * 0.6f),
                    fontSize = (wPx / density.density * 0.030).sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 2.5.sp,
                    textAlign = TextAlign.Center
                )
            )

            // Auth hint
            Text(
                text = stringResource(R.string.biometric_subtitle),
                modifier = Modifier.padding(top = 24.dp),
                style = TextStyle(
                    color = BorizonColors.TEXT.copy(alpha = titleAlpha.value * 0.4f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center
                )
            )

            // Error message + retry
            errorMessage?.let { msg ->
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = msg,
                    style = TextStyle(
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    ),
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        errorMessage = null
                        biometricPrompt?.authenticate(promptInfo)
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = BorizonColors.TEXT
                    )
                ) {
                    Text(stringResource(R.string.retry))
                }
            }
        }

        // Footer
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 48.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.biometric_data_stays),
                style = TextStyle(
                    color = BorizonColors.TEXT.copy(alpha = 0.3f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            )
        }
    }
}
