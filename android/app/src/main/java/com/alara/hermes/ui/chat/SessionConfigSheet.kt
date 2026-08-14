package com.alara.hermes.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alara.hermes.protocol.ModelOption
import com.alara.hermes.protocol.SessionConfig

private val REASONING_LEVELS = listOf("none", "low", "medium", "high", "max")

/** Advanced session controls: model, thinking level, fast mode. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionConfigSheet(
    config: SessionConfig,
    models: List<ModelOption>,
    onDismiss: () -> Unit,
    onModel: (ModelOption) -> Unit,
    onReasoning: (String) -> Unit,
    onFastMode: (Boolean) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Session settings", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))

            Text(
                "Thinking",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                REASONING_LEVELS.forEach { level ->
                    FilterChip(
                        selected = (config.thinkingLevel ?: "") == level ||
                            (level == "none" && config.thinkingLevel.isNullOrBlank()),
                        onClick = { onReasoning(level) },
                        label = { Text(level) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Fast mode", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Priority service tier for this session",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = config.fastMode == true,
                    onCheckedChange = onFastMode,
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))
            Text(
                "Model",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            if (models.isEmpty()) {
                Text(
                    "Model list unavailable — open a conversation first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                LazyColumn(Modifier.height(320.dp)) {
                    items(models, key = { "${it.provider}/${it.id}" }) { model ->
                        val selected = config.model == model.id
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onModel(model) }
                                .padding(vertical = 10.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(model.displayName, style = MaterialTheme.typography.bodyLarge)
                                model.provider?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
