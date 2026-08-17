package com.alara.hermes.ui.bots

import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.alara.hermes.protocol.HermesProfile
import com.alara.hermes.ui.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The native plugin's 10-colour avatar palette (docs/BOT_MODE_REFERENCE.md). */
internal val AVATAR_PALETTE = listOf(
    "#f5f5f4", "#8d6748", "#ef4444", "#f97316", "#14b8a6",
    "#38bdf8", "#3b40c8", "#8b5cf6", "#ec4899", "#9ca3af",
)

private const val MAX_AVATAR_BYTES = 2L * 1024 * 1024

/**
 * "Edit look" — backend-synced avatar editor (docs/BOT_APPEARANCE_API.md).
 * Presentation only; shape + colour save via PATCH appearance, photos via
 * the upload route. Desktop reads the same store, so both surfaces agree.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvatarEditorSheet(
    profile: HermesProfile,
    viewModel: HomeViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var shape by remember { mutableStateOf(profile.avatarShape ?: defaultShapeFor(profile.id)) }
    var color by remember { mutableStateOf(profile.avatarColor ?: "#f97316") }
    var saving by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            saving = true
            val payload = withContext(Dispatchers.IO) {
                runCatching {
                    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null || bytes.size > MAX_AVATAR_BYTES) null
                    else Base64.encodeToString(bytes, Base64.NO_WRAP) to mime
                }.getOrNull()
            }
            if (payload == null) {
                saving = false
                viewModel.showNotice("Couldn't read that image (2 MB max)")
            } else {
                viewModel.uploadAvatar(profile.id, payload.first, payload.second) { ok ->
                    saving = false
                    if (ok) onDismiss()
                }
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Text(
                "Edit look — ${profile.displayName}",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                GeometricAvatar(profile.id, shape, color, 72.dp)
            }
            if (!profile.avatarUrl.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "This bot currently shows an uploaded photo. Shape and colour " +
                        "apply after the photo is removed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text("Shape", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AVATAR_SHAPES.forEach { candidate ->
                    Box(
                        Modifier
                            .border(
                                width = if (candidate == shape) 2.dp else 0.dp,
                                color = if (candidate == shape) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.Transparent
                                },
                                shape = CircleShape,
                            )
                            .padding(4.dp)
                            .clickable { shape = candidate },
                    ) {
                        GeometricAvatar(profile.id, candidate, color, 40.dp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Colour", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AVATAR_PALETTE.forEach { hex ->
                    Box(
                        Modifier
                            .size(36.dp)
                            .border(
                                width = if (hex == color) 2.dp else 1.dp,
                                color = if (hex == color) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                                shape = CircleShape,
                            )
                            .padding(4.dp)
                            .background(
                                Color(android.graphics.Color.parseColor(hex)),
                                CircleShape,
                            )
                            .clickable { color = hex },
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    enabled = !saving,
                ) { Text("Use a photo…") }
                if (!profile.avatarUrl.isNullOrBlank()) {
                    TextButton(
                        onClick = {
                            saving = true
                            viewModel.editAppearance(profile.id, clearImage = true) { ok ->
                                saving = false
                                if (ok) onDismiss()
                            }
                        },
                        enabled = !saving,
                    ) { Text("Remove photo") }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        saving = true
                        viewModel.editAppearance(profile.id, shape = shape, color = color) { ok ->
                            saving = false
                            if (ok) onDismiss()
                        }
                    },
                    enabled = !saving,
                ) { Text(if (saving) "Saving…" else "Save") }
            }
        }
    }
}
