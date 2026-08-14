package com.alara.hermes.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.MarkdownComponent
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import com.mikepenz.markdown.compose.elements.highlightedCodeFence
import kotlinx.coroutines.delay
import org.intellij.markdown.ast.getTextInNode

/**
 * Syntax-highlighted code blocks with a copy button overlay.
 * Highlighting comes from the markdown-code module; the overlay extracts the
 * raw code from the AST so what's copied is the code, not the fences.
 */
val codeBlockWithCopy: MarkdownComponent = { model ->
    CodeContainer(codeOf(model.content, model.node.getTextInNode(model.content).toString())) {
        highlightedCodeBlock(model)
    }
}

val codeFenceWithCopy: MarkdownComponent = { model ->
    CodeContainer(codeOf(model.content, model.node.getTextInNode(model.content).toString())) {
        highlightedCodeFence(model)
    }
}

/** Strip ``` fence lines; indented code blocks pass through unchanged. */
private fun codeOf(@Suppress("UNUSED_PARAMETER") content: String, raw: String): String {
    val lines = raw.trim().lines()
    if (lines.firstOrNull()?.startsWith("```") != true) return raw.trimIndent()
    return lines
        .drop(1)
        .let { if (it.lastOrNull()?.trim() == "```") it.dropLast(1) else it }
        .joinToString("\n")
}

@Composable
private fun CodeContainer(code: String, inner: @Composable ColumnScope.() -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }
    Box(Modifier.fillMaxWidth()) {
        Column(content = inner)
        IconButton(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Code", code))
                copied = true
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(32.dp),
        ) {
            Icon(
                if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                contentDescription = if (copied) "Copied" else "Copy code",
                tint = if (copied) {
                    com.alara.hermes.ui.theme.HermesColors.Positive
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
