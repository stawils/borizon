package com.borizon.app.ui.screens


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.borizon.app.ui.components.BorizonCard
import com.borizon.app.ui.theme.LocalBorizonSemanticColors
import androidx.compose.ui.res.stringResource
import com.borizon.app.R
import com.borizon.app.ui.theme.SurfaceLevel

/**
 * Onboarding flow — shown on first launch.
 * 4 pages: Welcome, Name, Privacy, First Seed.
 * No email, no account. Everything stays on this device.
 */
@Composable
fun OnboardingScreen(
    onComplete: (userName: String) -> Unit
) {
    var currentPage by remember { mutableIntStateOf(0) }
    var userName by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Page content takes most of the space
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            when (currentPage) {
                0 -> WelcomePage()
                1 -> NamePage(
                    name = userName,
                    onNameChange = {
                        userName = it
                        nameError = false
                    },
                    showError = nameError
                )
                2 -> PrivacyPage()
                3 -> SeedPage(onComplete = { onComplete(userName) })
            }
        }

        // Page indicators and navigation
        BottomNav(
            currentPage = currentPage,
            totalPages = 4,
            canProceed = when (currentPage) {
                1 -> userName.isNotBlank()
                else -> true
            },
            onBack = {
                nameError = false
                if (currentPage > 0) currentPage--
            },
            onNext = {
                if (currentPage == 1 && userName.isBlank()) {
                    nameError = true
                } else if (currentPage < 3) {
                    currentPage++
                } else {
                    onComplete(userName)
                }
            }
        )
    }
}

@Composable
private fun WelcomePage() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.onboarding_welcome),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onboarding_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        FeatureRow(icon = Icons.Default.Lock, text = stringResource(R.string.onboarding_feature_private))
        Spacer(modifier = Modifier.height(8.dp))
        FeatureRow(icon = Icons.Default.Psychology, text = stringResource(R.string.onboarding_feature_ai))
        Spacer(modifier = Modifier.height(8.dp))
        FeatureRow(icon = Icons.Default.CloudOff, text = stringResource(R.string.onboarding_feature_offline))
    }
}

@Composable
private fun FeatureRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    BorizonCard(
        surfaceLevel = SurfaceLevel.Low,
        cornerSize = 12.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun NamePage(name: String, onNameChange: (String) -> Unit, showError: Boolean = false) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.PersonOutline,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.onboarding_name_prompt),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_name_privacy),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.onboarding_name_placeholder)) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
            isError = showError,
            supportingText = if (showError) {
                { Text(stringResource(R.string.onboarding_name_required), color = MaterialTheme.colorScheme.error) }
            } else null
        )
    }
}

@Composable
private fun PrivacyPage() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Shield,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.onboarding_privacy_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))

        PrivacyItem(
            icon = Icons.Default.PhoneAndroid,
            title = stringResource(R.string.onboarding_privacy_data_title),
            body = stringResource(R.string.onboarding_privacy_data_body)
        )
        Spacer(modifier = Modifier.height(12.dp))
        PrivacyItem(
            icon = Icons.Default.Fingerprint,
            title = stringResource(R.string.onboarding_privacy_bio_title),
            body = stringResource(R.string.onboarding_privacy_bio_body)
        )
        Spacer(modifier = Modifier.height(12.dp))
        PrivacyItem(
            icon = Icons.Default.DeleteForever,
            title = stringResource(R.string.onboarding_privacy_control_title),
            body = stringResource(R.string.onboarding_privacy_control_body)
        )
    }
}

@Composable
private fun PrivacyItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    BorizonCard(
        surfaceLevel = SurfaceLevel.Low,
        cornerSize = 12.dp,
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SeedPage(onComplete: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.ChatBubbleOutline,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.onboarding_ready_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onboarding_ready_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onComplete,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.get_started), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun BottomNav(
    currentPage: Int,
    totalPages: Int,
    canProceed: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back button
            TextButton(
                onClick = onBack,
                enabled = currentPage > 0
            ) {
                Text(stringResource(R.string.back))
            }

            Spacer(modifier = Modifier.weight(1f))

            // Page indicators
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(totalPages) { index ->
                    Box(
                        modifier = Modifier
                            .size(if (index == currentPage) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == currentPage) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Next / Skip button
            if (currentPage < 3) {
                TextButton(
                    onClick = onNext,
                    enabled = canProceed
                ) {
                    Text(
                        if (currentPage == 1 && !canProceed) stringResource(R.string.enter_your_name) else stringResource(R.string.next)
                    )
                }
            }
        }
    }
}
