package com.borizon.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.borizon.app.R

data class ModelConfig(
    val temperature: Float = 0.75f,
    val topK: Int = 40,
    val topP: Float = 0.90f,
    val maxTokens: Int = 8192,
    val enableThinking: Boolean = false,
    val enableMtp: Boolean = true,
    val accelerator: String = "auto", // auto, cpu, gpu, npu
) {
    /** Human-readable summary of what changed vs another config. */
    fun diffFrom(other: ModelConfig): Map<String, Pair<String, String>> {
        val changes = mutableMapOf<String, Pair<String, String>>()
        if (temperature != other.temperature)
            changes["Temperature"] = String.format("%.2f", other.temperature) to String.format("%.2f", temperature)
        if (topK != other.topK)
            changes["Top-K"] = other.topK.toString() to topK.toString()
        if (topP != other.topP)
            changes["Top-P"] = String.format("%.2f", other.topP) to String.format("%.2f", topP)
        if (maxTokens != other.maxTokens)
            changes["Max Tokens"] = other.maxTokens.toString() to maxTokens.toString()
        if (enableThinking != other.enableThinking)
            changes["Thinking"] = (if (other.enableThinking) "On" else "Off") to (if (enableThinking) "On" else "Off")
        if (enableMtp != other.enableMtp)
            changes["MTP"] = (if (other.enableMtp) "On" else "Off") to (if (enableMtp) "On" else "Off")
        if (accelerator != other.accelerator)
            changes["Accelerator"] = other.accelerator.uppercase() to accelerator.uppercase()
        return changes
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelConfigSheet(
    initialConfig: ModelConfig = ModelConfig(),
    onDismiss: () -> Unit,
    onApply: (ModelConfig) -> Unit
) {
    var temperature by remember { mutableFloatStateOf(initialConfig.temperature) }
    var topK by remember { mutableFloatStateOf(initialConfig.topK.toFloat()) }
    var topP by remember { mutableFloatStateOf(initialConfig.topP) }
    var maxTokens by remember { mutableFloatStateOf(initialConfig.maxTokens.toFloat()) }
    var enableThinking by remember { mutableStateOf(initialConfig.enableThinking) }
    var enableMtp by remember { mutableStateOf(initialConfig.enableMtp) }
    var accelerator by remember { mutableStateOf(initialConfig.accelerator) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = stringResource(R.string.model_config_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.model_config_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Accelerator selector
            Text(
                text = stringResource(R.string.model_config_accelerator),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.model_config_accelerator_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("auto", "cpu", "gpu", "npu").forEach { opt ->
                    FilterChip(
                        selected = accelerator == opt,
                        onClick = { accelerator = opt },
                        label = { Text(opt.uppercase(), style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Thinking toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.model_config_thinking),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.model_config_thinking_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enableThinking,
                    onCheckedChange = { enableThinking = it }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // MTP (speculative decoding) toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.model_config_mtp),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.model_config_mtp_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enableMtp,
                    onCheckedChange = { enableMtp = it }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Temperature
            ConfigSlider(
                label = stringResource(R.string.model_config_temperature),
                description = stringResource(R.string.model_config_temperature_desc),
                value = temperature,
                range = 0f..2f,
                valueLabel = String.format("%.2f", temperature),
                onValueChange = { temperature = it }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Top-K
            ConfigSlider(
                label = stringResource(R.string.model_config_top_k),
                description = stringResource(R.string.model_config_top_k_desc),
                value = topK,
                range = 1f..100f,
                valueLabel = topK.roundToInt().toString(),
                onValueChange = { topK = it }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Top-P
            ConfigSlider(
                label = stringResource(R.string.model_config_top_p),
                description = stringResource(R.string.model_config_top_p_desc),
                value = topP,
                range = 0f..1f,
                valueLabel = String.format("%.2f", topP),
                onValueChange = { topP = it }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Max tokens (KV cache size / context window)
            // Range extends to 8192 — loadModel() will clamp to the device's adaptive
            // ceiling at runtime. The slider lets the user choose freely; the actual
            // value used is min(user choice, device ceiling).
            ConfigSlider(
                label = stringResource(R.string.model_config_context),
                description = stringResource(R.string.model_config_context_desc),
                value = maxTokens,
                range = 1024f..8192f,
                valueLabel = maxTokens.roundToInt().toString(),
                onValueChange = { maxTokens = (it / 256f).roundToInt() * 256f }
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = {
                        onApply(
                            ModelConfig(
                                temperature = temperature,
                                topK = topK.roundToInt(),
                                topP = topP,
                                maxTokens = maxTokens.roundToInt(),
                                enableThinking = enableThinking,
                                enableMtp = enableMtp,
                                accelerator = accelerator,
                            )
                        )
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.apply))
                }
            }
        }
    }
}

@Composable
private fun ConfigSlider(
    label: String,
    description: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = valueLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
