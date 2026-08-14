package com.alara.hermes.ui.chat

import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.alara.hermes.protocol.OutgoingAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_ATTACHMENTS = 4
private const val MAX_ATTACHMENT_BYTES = 8L * 1024 * 1024

private data class PendingAttachment(
    val uri: Uri,
    val attachment: OutgoingAttachment,
)

/**
 * Messaging composer with image attachments. Draft is restored per session
 * and persisted with a small debounce so switching sessions never loses
 * typed text.
 */
@Composable
fun Composer(
    sessionKey: String?,
    sending: Boolean,
    running: Boolean,
    offline: Boolean,
    onSend: (String, List<OutgoingAttachment>) -> Unit,
    onStop: () -> Unit,
    draftLoader: suspend (String) -> String,
    onDraftChange: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable(sessionKey) { mutableStateOf("") }
    var draftLoaded by remember(sessionKey) { mutableStateOf(false) }
    val pending = remember(sessionKey) { mutableStateListOf<PendingAttachment>() }
    var attachmentError by remember { mutableStateOf<String?>(null) }

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS),
    ) { uris ->
        scope.launch {
            attachmentError = null
            uris.take(MAX_ATTACHMENTS - pending.size).forEach { uri ->
                val loaded = withContext(Dispatchers.IO) {
                    runCatching {
                        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: return@runCatching null
                        if (bytes.size > MAX_ATTACHMENT_BYTES) return@runCatching null
                        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "image"
                        PendingAttachment(
                            uri = uri,
                            attachment = OutgoingAttachment(
                                name = name,
                                mimeType = mime,
                                dataBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                            ),
                        )
                    }.getOrNull()
                }
                if (loaded != null) {
                    pending += loaded
                } else {
                    attachmentError = "Skipped an image (unreadable or over 8 MB)"
                }
            }
        }
    }

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

    Column(modifier) {
        if (offline) {
            Text(
                "Offline — reconnecting…",
                style = MaterialTheme.typography.labelSmall,
                color = com.alara.hermes.ui.theme.HermesColors.Caution,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        attachmentError?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        if (pending.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                pending.forEach { item ->
                    Box(Modifier.padding(end = 8.dp)) {
                        AsyncImage(
                            model = item.uri,
                            contentDescription = item.attachment.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(2.dp)
                                .size(20.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                                .clickable { pending.remove(item) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove attachment",
                                modifier = Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 8.dp),
        ) {
            IconButton(
                onClick = {
                    if (pending.size < MAX_ATTACHMENTS) {
                        pickImages.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    }
                },
                enabled = pending.size < MAX_ATTACHMENTS,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Attach image",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 8,
                decorationBox = { inner ->
                    Box(
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
            Spacer(Modifier.padding(3.dp))
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
                    if (message.isNotEmpty() || pending.isNotEmpty()) {
                        onSend(message, pending.map { it.attachment })
                        text = ""
                        pending.clear()
                    }
                },
                enabled = (text.isNotBlank() || pending.isNotEmpty()) && !sending,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}
