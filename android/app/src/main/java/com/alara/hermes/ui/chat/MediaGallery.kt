package com.alara.hermes.ui.chat

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.alara.hermes.util.MediaKind
import com.alara.hermes.util.MediaLink
import okhttp3.OkHttpClient

/**
 * Inline media referenced by a message. Images render as tappable previews
 * with a fullscreen viewer; video plays in place through Media3 with normal
 * transport controls, and expands to a fullscreen dialog.
 *
 * [authHeader] is attached only for URLs on the gateway host so private
 * artifacts load without leaking the key to third-party hosts.
 */
@Composable
fun MediaGallery(
    links: List<MediaLink>,
    gatewayHost: String?,
    authHeader: String?,
) {
    if (links.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val images = links.filter { it.kind == MediaKind.IMAGE }
        val videos = links.filter { it.kind == MediaKind.VIDEO }
        val audio = links.filter { it.kind == MediaKind.AUDIO }

        // Image grid: pairs per row, media-app style.
        images.chunked(2).forEach { rowLinks ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowLinks.forEach { link ->
                    Box(Modifier.weight(1f)) {
                        InlineImage(link.url, gatewayHost, authHeader)
                    }
                }
                if (rowLinks.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        videos.forEach { link -> InlineVideo(link.url, gatewayHost, authHeader) }
        audio.forEach { link -> InlineAudio(link.url, gatewayHost, authHeader) }
    }
}

private fun headersFor(url: String, gatewayHost: String?, authHeader: String?): Map<String, String> {
    if (authHeader == null || gatewayHost == null) return emptyMap()
    val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return emptyMap()
    return if (host.equals(gatewayHost, ignoreCase = true)) mapOf("Authorization" to authHeader) else emptyMap()
}

@Composable
private fun InlineImage(url: String, gatewayHost: String?, authHeader: String?) {
    val context = LocalContext.current
    var fullscreen by rememberSaveable(url) { mutableStateOf(false) }
    val request = remember(url) {
        ImageRequest.Builder(context)
            .data(url)
            .apply {
                headersFor(url, gatewayHost, authHeader).forEach { (k, v) -> setHeader(k, v) }
            }
            .crossfade(true)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = "Image attachment",
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { fullscreen = true },
    )
    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { fullscreen = false },
            ) {
                AsyncImage(
                    model = request,
                    contentDescription = "Image attachment",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    IconButton(onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, url)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share image"))
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share", tint = Color.White)
                    }
                    IconButton(onClick = { fullscreen = false }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineVideo(url: String, gatewayHost: String?, authHeader: String?) {
    var playing by rememberSaveable(url) { mutableStateOf(false) }
    if (!playing) {
        // Lazy: no player, no network, until the user asks.
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable { playing = true },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Play video",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(32.dp),
                )
            }
            Text(
                url.substringAfterLast('/').substringBefore('?'),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp),
            )
        }
    } else {
        VideoPlayer(url, gatewayHost, authHeader)
    }
}

@Composable
private fun VideoPlayer(url: String, gatewayHost: String?, authHeader: String?) {
    val context = LocalContext.current
    val player = remember(url) {
        val headers = headersFor(url, gatewayHost, authHeader)
        val httpFactory = OkHttpDataSource.Factory(OkHttpClient())
            .setDefaultRequestProperties(headers)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory)),
            )
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(url) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                setShowNextButton(false)
                setShowPreviousButton(false)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp)),
    )
}

@Composable
private fun InlineAudio(url: String, gatewayHost: String?, authHeader: String?) {
    var playing by rememberSaveable(url) { mutableStateOf(false) }
    if (!playing) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable { playing = true }
                .padding(12.dp),
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Play audio",
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                url.substringAfterLast('/').substringBefore('?'),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    } else {
        val context = LocalContext.current
        val player = remember(url) {
            val headers = headersFor(url, gatewayHost, authHeader)
            val httpFactory = OkHttpDataSource.Factory(OkHttpClient())
                .setDefaultRequestProperties(headers)
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory)),
                )
                .build()
                .apply {
                    setMediaItem(MediaItem.fromUri(url))
                    prepare()
                    playWhenReady = true
                }
        }
        DisposableEffect(url) { onDispose { player.release() } }
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    controllerShowTimeoutMs = 0
                    controllerHideOnTouch = false
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
    }
}
