package com.borizon.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.borizon.app.R
import com.borizon.app.ui.theme.HeroTitle
import com.borizon.app.ui.theme.LocalBorizonSemanticColors
import kotlinx.coroutines.delay

@Composable
fun WelcomeScreen(
    onStart: () -> Unit = {}
) {
    val semanticColors = LocalBorizonSemanticColors.current
    var phase by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        delay(600)
        phase = 1
        delay(800)
        phase = 2
        delay(600)
        phase = 3
        delay(400)
        phase = 4
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = HeroTitle,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.alpha(animateFloatAsState(if (phase >= 1) 1f else 0f, label = "title").value)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.welcome_subtitle),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Normal,
                color = semanticColors.ui.emptyStateTitleColor,
                modifier = Modifier.alpha(animateFloatAsState(if (phase >= 2) 1f else 0f, label = "subtitle").value)
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = stringResource(R.string.welcome_body),
                style = MaterialTheme.typography.bodyLarge,
                color = semanticColors.ui.emptyStateDescColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(animateFloatAsState(if (phase >= 3) 1f else 0f, label = "desc").value)
            )

            Spacer(modifier = Modifier.height(48.dp))

            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .alpha(animateFloatAsState(if (phase >= 4) 1f else 0f, label = "btn").value),
                shape = MaterialTheme.shapes.large
            ) {
                Text(stringResource(R.string.begin), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
