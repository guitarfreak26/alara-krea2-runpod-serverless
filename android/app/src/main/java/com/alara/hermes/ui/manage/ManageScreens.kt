package com.alara.hermes.ui.manage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alara.hermes.protocol.AutomationInfo
import com.alara.hermes.protocol.SkillInfo
import com.alara.hermes.ui.HomeViewModel
import com.alara.hermes.ui.theme.HermesColors
import com.alara.hermes.util.formatRelativeTime
import kotlinx.coroutines.launch

@Composable
private fun ManageScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onBack)
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(horizontal = 4.dp),
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Text(title, style = MaterialTheme.typography.titleMedium)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            content()
        }
    }
}

@Composable
fun SkillsScreen(viewModel: HomeViewModel, onBack: () -> Unit) {
    var skills by remember { mutableStateOf<List<SkillInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.skills()
            .onSuccess { skills = it }
            .onFailure { error = viewModel.cleanError(it.message) }
    }

    ManageScaffold(title = "Skills", onBack = onBack) {
        when {
            error != null -> CenteredMessage(error!!)
            skills == null -> CenteredLoading()
            skills!!.isEmpty() -> CenteredMessage("No skills installed on this profile")
            else -> {
                val grouped = skills!!.groupBy { it.category.ifBlank { "general" } }
                LazyColumn(Modifier.fillMaxSize()) {
                    grouped.toSortedMap().forEach { (category, rows) ->
                        item(key = "cat-$category") {
                            Text(
                                category,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                            )
                        }
                        items(rows, key = { "${category}/${it.name}" }) { skill ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        skill.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (skill.disabled) {
                                        Text(
                                            "disabled",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (skill.description.isNotBlank()) {
                                    Text(
                                        skill.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AutomationsScreen(viewModel: HomeViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var jobs by remember { mutableStateOf<List<AutomationInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<AutomationInfo?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        viewModel.automations()
            .onSuccess { jobs = it; error = null }
            .onFailure { error = viewModel.cleanError(it.message) }
    }
    LaunchedEffect(Unit) { reload() }

    ManageScaffold(title = "Automations", onBack = onBack) {
        notice?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = HermesColors.Positive,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        when {
            error != null -> CenteredMessage(error!!)
            jobs == null -> CenteredLoading()
            jobs!!.isEmpty() -> CenteredMessage("No automations configured")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(jobs!!, key = { it.id }) { job ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(job.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    buildString {
                                        append(job.schedule.ifBlank { "no schedule" })
                                        if (job.paused) append("  ·  paused")
                                        else if (!job.enabled) append("  ·  disabled")
                                        job.lastRunAtMs?.let {
                                            append("  ·  ran ").append(formatRelativeTime(it))
                                        }
                                        job.lastStatus?.let { append("  ·  ").append(it) }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (job.paused || !job.enabled) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        HermesColors.Positive
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    viewModel.setAutomationPaused(job.id, !job.paused)
                                        .onFailure { error = viewModel.cleanError(it.message) }
                                    reload()
                                }
                            }) {
                                Icon(
                                    if (job.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                    contentDescription = if (job.paused) "Resume" else "Pause",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    viewModel.runAutomation(job.id)
                                        .onSuccess { notice = "\"${job.name}\" started" }
                                        .onFailure { error = viewModel.cleanError(it.message) }
                                }
                            }) {
                                Icon(
                                    Icons.Filled.RocketLaunch,
                                    contentDescription = "Run now",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            IconButton(onClick = { deleteTarget = job }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (job.prompt.isNotBlank()) {
                            Text(
                                job.prompt,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }
        }
    }

    deleteTarget?.let { job ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete automation?") },
            text = { Text("\"${job.name}\" will be removed from the Hermes server. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        viewModel.deleteAutomation(job.id)
                            .onFailure { error = viewModel.cleanError(it.message) }
                        reload()
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CenteredLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row {
            Spacer(Modifier.width(24.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(24.dp))
        }
    }
}
