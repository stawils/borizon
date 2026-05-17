package com.borizon.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.borizon.app.ui.theme.SurfaceLevel
import com.borizon.app.R

/**
 * Bottom sheet for managing skills: toggle, import, and delete.
 *
 * Built-in skills can only be toggled; imported skills can also be deleted.
 * Import is via SAF (Storage Access Framework) — directory or individual file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillManagerSheet(
    skills: List<com.borizon.app.proto.Skill>,
    onToggleSkill: (name: String, selected: Boolean) -> Unit,
    onDeleteSkill: (name: String) -> Unit,
    onImportFromDirectory: () -> Unit,
    onImportFromFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Text(
                stringResource(R.string.skills_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            val activeCount = skills.count { it.selected }
            Text(
                "$activeCount of ${skills.size} active",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            // Skill list
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.height(320.dp),
            ) {
                items(skills, key = { it.name }) { skill ->
                    val hasJs = skill.instructions.contains("runJs", ignoreCase = true)
                    SkillRow(
                        name = skill.name,
                        description = skill.description,
                        isEnabled = skill.selected,
                        isBuiltIn = skill.builtIn,
                        hasJs = hasJs,
                        onToggle = { onToggleSkill(skill.name, it) },
                        onDelete = { onDeleteSkill(skill.name) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Import buttons
            Text(
                stringResource(R.string.skills_import),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onImportFromDirectory,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.skills_folder))
                }
                OutlinedButton(
                    onClick = onImportFromFile,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Default.InsertDriveFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.skills_file))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                stringResource(R.string.skills_import_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun SkillRow(
    name: String,
    description: String,
    isEnabled: Boolean,
    isBuiltIn: Boolean,
    hasJs: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    BorizonCard(
        surfaceLevel = SurfaceLevel.Low,
        cornerSize = 10.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Icon
            Icon(
                imageVector = if (hasJs) Icons.Default.Code else Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = if (isEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))

            // Name + description
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (isEnabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                if (description.isNotBlank()) {
                    Text(
                        description.take(60) + if (description.length > 60) "..." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                    )
                }
            }

            // Delete button for imported skills
            if (!isBuiltIn) {
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Toggle switch
            BorizonSwitch(
                checked = isEnabled,
                onCheckedChange = onToggle,
            )
        }
    }
}
