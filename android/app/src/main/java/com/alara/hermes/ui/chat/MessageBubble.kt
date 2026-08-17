package com.alara.hermes.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.alara.hermes.protocol.ChatEntry
import com.alara.hermes.protocol.Role
import com.alara.hermes.util.extractMediaLinks
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography

/** Auth context for loading private media from the gateway host. */
data class MediaAuth(val host: String, val header: String)

/** Slightly tighter than bodyLarge: chat threads read better a step smaller. */
private val messageTextStyle: TextStyle
    @Composable get() = MaterialTheme.typography.bodyLarge.copy(
        fontSize = 15.sp,
        lineHeight = 21.sp,
    )

/**
 * Chat bubble. User turns get a tinted right-aligned bubble; assistant turns
 * render as full-width markdown on the AMOLED ground, iMessage/ChatGPT hybrid.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MessageBubble(
    entry: ChatEntry.Message,
    mediaAuth: MediaAuth? = null,
    streamLive: Boolean = true,
    mediaPathUrl: ((String) -> String?)? = null,
) {
    val context = LocalContext.current
    var actionsOpen by remember { mutableStateOf(false) }

    when (entry.role) {
        Role.USER -> {
            // Never span the full width: outgoing bubbles read as "mine".
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 48.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
            ) {
                Box(
                    Modifier
                        .widthIn(max = 560.dp)
                        .background(
                            com.alara.hermes.ui.theme.HermesColors.BubbleOutgoing,
                            RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp),
                        )
                        .combinedClickable(onClick = {}, onLongClick = { actionsOpen = true })
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Column {
                        if (entry.attachments.isNotEmpty()) {
                            Row(
                                modifier = Modifier.padding(bottom = 6.dp),
                            ) {
                                entry.attachments.take(4).forEach { attachment ->
                                    coil.compose.AsyncImage(
                                        model = attachment.url,
                                        contentDescription = attachment.name,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier
                                            .padding(end = 6.dp)
                                            .size(72.dp)
                                            .clip(RoundedCornerShape(8.dp)),
                                    )
                                }
                            }
                        }
                        if (entry.text.isNotBlank()) {
                            Text(
                                entry.text,
                                style = messageTextStyle,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    }
                }
            }
        }
        Role.ASSISTANT -> {
            // Incoming bubble, mirrored corner from the user's. End margin keeps
            // it visually "received" without wasting width for long content.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(end = 32.dp),
            ) {
                Box(
                    Modifier
                        .background(
                            com.alara.hermes.ui.theme.HermesColors.BubbleIncoming,
                            RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 6.dp, bottomEnd = 18.dp),
                        )
                        .combinedClickable(onClick = {}, onLongClick = { actionsOpen = true })
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Column {
                        when {
                            entry.streaming && !streamLive -> TypingIndicator()
                            entry.streaming -> {
                                // Markdown re-parses the whole document per token and
                                // makes streaming stutter; render plain text with an
                                // inline caret and switch to markdown on completion.
                                Text(
                                    if (entry.text.isEmpty()) "…" else entry.text + " ▍",
                                    style = messageTextStyle,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            else -> {
                                // MEDIA: directives render as players, not paths;
                                // lone newlines become hard breaks so messenger-
                                // style replies don't glue into text walls.
                                val display = com.alara.hermes.protocol.ChatFormatting.chatLineBreaks(
                                    com.alara.hermes.protocol.MediaDirectives.strip(entry.text),
                                )
                                Markdown(
                                    content = display.ifBlank { entry.text },
                                    components = markdownComponents(
                                        codeBlock = codeBlockWithCopy,
                                        codeFence = codeFenceWithCopy,
                                    ),
                                    typography = chatMarkdownTypography(),
                                )
                                MediaGallery(
                                    links = extractMediaLinks(entry.text, mediaPathUrl),
                                    gatewayHost = mediaAuth?.host,
                                    authHeader = mediaAuth?.header,
                                )
                            }
                        }
                    }
                }
            }
        }
        Role.SYSTEM -> {
            Text(
                entry.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            )
        }
    }

    if (actionsOpen) {
        ModalBottomSheet(onDismissRequest = { actionsOpen = false }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                MessageAction("Copy") {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Hermes message", entry.text))
                    actionsOpen = false
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MessageAction("Share") {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, entry.text)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share message"))
                    actionsOpen = false
                }
            }
        }
    }
}

/**
 * Chat-scale markdown: body text matches [messageTextStyle], headings step
 * down from the huge display styles, and paragraphs keep clear spacing.
 */
@Composable
private fun chatMarkdownTypography() = markdownTypography(
    h1 = MaterialTheme.typography.titleLarge,
    h2 = MaterialTheme.typography.titleMedium,
    h3 = MaterialTheme.typography.titleSmall,
    h4 = MaterialTheme.typography.titleSmall,
    h5 = MaterialTheme.typography.titleSmall,
    h6 = MaterialTheme.typography.labelLarge,
    text = messageTextStyle,
    paragraph = messageTextStyle,
    ordered = messageTextStyle,
    bullet = messageTextStyle,
    list = messageTextStyle,
)

/** iMessage-style pulsing dots shown while a reply streams with live text off. */
@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row {
        (0..2).forEach { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = index * 200, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Text(
                "●",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .alpha(alpha),
            )
        }
    }
}

@Composable
private fun MessageAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
    )
}
