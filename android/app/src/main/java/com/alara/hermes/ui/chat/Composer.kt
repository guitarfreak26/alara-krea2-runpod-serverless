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
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
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

private const val MAX_ATTACHMENTS = 8
private const val MAX_ATTACHMENT_BYTES = 8L * 1024 * 1024
private const val MAX_TEXT_FILE_BYTES = 384L * 1024

private data class PendingAttachment(
    val uri: Uri,
    val attachment: OutgoingAttachment,
)

/** A readable text/code file embedded into the message body at send time. */
private data class PendingTextFile(
    val name: String,
    val content: String,
)

private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "markdown", "json", "yaml", "yml", "toml", "csv", "tsv", "xml",
    "html", "css", "js", "ts", "tsx", "jsx", "py", "kt", "kts", "java", "rs",
    "go", "rb", "sh", "bash", "sql", "log", "ini", "cfg", "conf", "env",
    "gradle", "properties", "diff", "patch", "srt", "vtt",
)

private fun looksTextual(name: String, mime: String?): Boolean {
    if (mime != null && (mime.startsWith("text/") || mime == "application/json" ||
            mime == "application/xml" || mime == "application/x-yaml")
    ) {
        return true
    }
    return name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS
}

/** Fence embedded files so the agent sees name + content unambiguously. */
private fun embedTextFiles(text: String, files: List<PendingTextFile>): String {
    if (files.isEmpty()) return text
    return buildString {
        files.forEach { file ->
            append("File: ").append(file.name).append('\n')
            append("```").append(file.name.substringAfterLast('.', "")).append('\n')
            append(file.content.trimEnd('\n')).append('\n')
            append("```").append('\n').append('\n')
        }
        append(text)
    }
}

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
    onSend: (String, List<OutgoingAttachment>, List<String>) -> Unit,
    onStop: () -> Unit,
    draftLoader: suspend (String) -> String,
    onDraftChange: (String, String) -> Unit,
    pendingShare: com.alara.hermes.SharePayload? = null,
    onShareConsumed: () -> Unit = {},
    /** Room context: the @ picker offers ONLY these members. */
    roomMembers: List<com.alara.hermes.protocol.RoomMember> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable(sessionKey) { mutableStateOf("") }
    var draftLoaded by remember(sessionKey) { mutableStateOf(false) }
    val pending = remember(sessionKey) { mutableStateListOf<PendingAttachment>() }
    val pendingText = remember(sessionKey) { mutableStateListOf<PendingTextFile>() }
    var attachmentError by remember { mutableStateOf<String?>(null) }
    var attachMenuOpen by remember { mutableStateOf(false) }
    var mentionMenuOpen by remember { mutableStateOf(false) }

    // Mentions picked in this draft: profileId -> inserted label. The IDs
    // travel STRUCTURALLY to the send payload (kept only while their @label
    // is still present in the text); text scanning is just a fallback for
    // hand-typed mentions.
    val pickedMentions = remember(sessionKey) { androidx.compose.runtime.mutableStateMapOf<String, String>() }

    fun mentionsForSend(message: String): List<String> {
        val picked = pickedMentions.filterValues { label ->
            message.contains("@$label", ignoreCase = true)
        }.keys
        val typed = roomMembers.filter { member ->
            val firstWord = member.displayName.substringBefore(' ')
            message.contains("@${member.mentionLabel}", ignoreCase = true) ||
                message.contains("@${member.displayName}", ignoreCase = true) ||
                message.contains("@${member.profileId}", ignoreCase = true) ||
                (firstWord.length >= 3 && message.contains("@$firstWord", ignoreCase = true))
        }.map { it.profileId }
        return (picked + typed).distinct()
    }

    fun insertMention(member: com.alara.hermes.protocol.RoomMember) {
        val label = member.mentionLabel
        pickedMentions[member.profileId] = label
        text = if (text.endsWith("@")) {
            // Triggered by typing '@': complete the token in place.
            text.dropLast(1) + "@$label "
        } else {
            val prefix = if (text.isEmpty() || text.endsWith(" ")) "" else " "
            "$text$prefix@$label "
        }
    }

    // Shared by the file picker and the system share-sheet ingress.
    suspend fun importUri(uri: Uri) {
        val mime = context.contentResolver.getType(uri)
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@runCatching null
                when {
                    mime?.startsWith("image/") == true -> {
                        if (bytes.size > MAX_ATTACHMENT_BYTES || pending.size >= MAX_ATTACHMENTS) {
                            null
                        } else {
                            PendingAttachment(
                                uri,
                                OutgoingAttachment(name, mime, Base64.encodeToString(bytes, Base64.NO_WRAP)),
                            )
                        }
                    }
                    looksTextual(name, mime) -> {
                        if (bytes.size > MAX_TEXT_FILE_BYTES) {
                            null
                        } else {
                            PendingTextFile(name, bytes.toString(Charsets.UTF_8))
                        }
                    }
                    else -> "unsupported"
                }
            }.getOrNull()
        }.let { loaded ->
            when (loaded) {
                is PendingAttachment -> pending += loaded
                is PendingTextFile -> pendingText += loaded
                "unsupported" -> attachmentError =
                    "\"$name\" isn't supported — images and text/code files only on this surface"
                else -> attachmentError = "Skipped \"$name\" (unreadable or too large)"
            }
        }
    }

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        scope.launch {
            attachmentError = null
            uris.forEach { importUri(it) }
        }
    }

    // Content shared in from another app lands as if it were picked here.
    LaunchedEffect(pendingShare) {
        val payload = pendingShare ?: return@LaunchedEffect
        payload.text?.takeIf { it.isNotBlank() }?.let { shared ->
            text = if (text.isBlank()) shared else "$text\n$shared"
        }
        payload.uris.forEach { importUri(it) }
        onShareConsumed()
    }

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
        if (pendingText.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 2.dp),
            ) {
                pendingText.forEach { file ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainer,
                                RoundedCornerShape(14.dp),
                            )
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Icon(
                            Icons.Filled.Description,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.padding(2.dp))
                        Text(
                            file.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.padding(2.dp))
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Remove ${file.name}",
                            modifier = Modifier
                                .size(14.dp)
                                .clickable { pendingText.remove(file) },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
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
            Box {
                IconButton(onClick = { attachMenuOpen = true }) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Attach",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = attachMenuOpen,
                    onDismissRequest = { attachMenuOpen = false },
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Photos") },
                        onClick = {
                            attachMenuOpen = false
                            if (pending.size < MAX_ATTACHMENTS) {
                                pickImages.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            }
                        },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Files") },
                        onClick = {
                            attachMenuOpen = false
                            pickFiles.launch(arrayOf("*/*"))
                        },
                    )
                }
            }
            if (roomMembers.isNotEmpty()) {
                Box {
                    IconButton(onClick = { mentionMenuOpen = true }) {
                        Icon(
                            Icons.Filled.AlternateEmail,
                            contentDescription = "Mention a member",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = mentionMenuOpen,
                        onDismissRequest = { mentionMenuOpen = false },
                    ) {
                        Text(
                            "Mention a member",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                        roomMembers.forEach { member ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("@${member.mentionLabel}")
                                        Text(
                                            listOfNotNull(
                                                member.displayName.takeIf { it != member.mentionLabel },
                                                member.role,
                                            ).joinToString("  ·  "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    mentionMenuOpen = false
                                    insertMention(member)
                                },
                            )
                        }
                    }
                }
            }
            BasicTextField(
                value = text,
                onValueChange = { new ->
                    // Typing '@' at a word start pops the room member picker.
                    val typedAt = roomMembers.isNotEmpty() &&
                        new.length == text.length + 1 && new.endsWith("@") &&
                        (new.length == 1 || new[new.length - 2].isWhitespace())
                    text = new
                    if (typedAt) mentionMenuOpen = true
                },
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
                    val message = embedTextFiles(text.trim(), pendingText.toList())
                    if (message.isNotEmpty() || pending.isNotEmpty()) {
                        onSend(message, pending.map { it.attachment }, mentionsForSend(message))
                        text = ""
                        pending.clear()
                        pendingText.clear()
                        pickedMentions.clear()
                    }
                },
                enabled = (text.isNotBlank() || pending.isNotEmpty() || pendingText.isNotEmpty()) && !sending,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}
