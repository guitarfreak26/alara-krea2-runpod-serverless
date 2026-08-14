package com.alara.hermes.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Messaging composer. Draft is restored per session and persisted with a small
 * debounce so switching sessions never loses typed text.
 */
@OptIn(FlowPreview::class)
@Composable
fun Composer(
    sessionKey: String?,
    sending: Boolean,
    running: Boolean,
    offline: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    draftLoader: suspend (String) -> String,
    onDraftChange: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable(sessionKey) { mutableStateOf("") }
    var draftLoaded by remember(sessionKey) { mutableStateOf(false) }

    LaunchedEffect(sessionKey) {
        if (sessionKey != null && !draftLoaded) {
            val draft = draftLoader(sessionKey)
            if (draft.isNotBlank() && text.isBlank()) text = draft
            draftLoaded = true
        }
    }
    // Debounced draft persistence.
    LaunchedEffect(sessionKey, text) {
        if (sessionKey != null && draftLoaded) {
            delay(400)
            onDraftChange(sessionKey, text)
        }
    }

    androidx.compose.foundation.layout.Column(modifier) {
        if (offline) {
            Text(
                "Offline — reconnecting…",
                style = MaterialTheme.typography.labelSmall,
                color = com.alara.hermes.ui.theme.HermesColors.Caution,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 8,
                decorationBox = { inner ->
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .background(
                                MaterialTheme.colorScheme.surfaceContainer,
                                RoundedCornerShape(22.dp),
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        if (text.isEmpty()) {
                            Text(
                                "Message Hermes",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp, max = 200.dp),
            )
            androidx.compose.foundation.layout.Spacer(Modifier.padding(3.dp))
            if (running || sending) {
                FilledIconButton(
                    onClick = onStop,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = "Stop",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            FilledIconButton(
                onClick = {
                    val message = text.trim()
                    if (message.isNotEmpty()) {
                        onSend(message)
                        text = ""
                    }
                },
                enabled = text.isNotBlank() && !sending,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}
