package com.alara.hermes.ui.manage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alara.hermes.protocol.AccountUsage
import com.alara.hermes.protocol.TokenTotals
import com.alara.hermes.protocol.UsageSummary
import com.alara.hermes.protocol.wire.HermesHttpException
import com.alara.hermes.ui.HomeViewModel
import com.alara.hermes.ui.theme.HermesColors
import com.alara.hermes.util.formatRelativeTime
import java.util.Locale

/**
 * Usage: provider allowance bars (CodexBar-style, served by the VPS which
 * holds the provider credentials) + token/cost totals from stored sessions.
 */
@Composable
fun UsageScreen(viewModel: HomeViewModel, onBack: () -> Unit) {
    var allowance by remember { mutableStateOf<List<AccountUsage>?>(null) }
    var allowanceMissing by remember { mutableStateOf(false) }
    var allowanceError by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<UsageSummary?>(null) }
    var summaryError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.accountUsage()
            .onSuccess { allowance = it }
            .onFailure { t ->
                if ((t as? HermesHttpException)?.code == 404) {
                    allowanceMissing = true
                } else {
                    allowanceError = viewModel.cleanError(t.message)
                }
            }
        viewModel.usageSummary()
            .onSuccess { summary = it }
            .onFailure { summaryError = viewModel.cleanError(it.message) }
    }

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
                        Text("Usage", style = MaterialTheme.typography.titleMedium)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(
                    "Provider allowance",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                when {
                    allowanceMissing -> Text(
                        "Your Hermes build doesn't expose provider limits yet. " +
                            "It already collects them internally (Codex windows, Claude limits, credits) — " +
                            "it just needs a small GET /v1/usage endpoint on the API server. " +
                            "The app will light this section up automatically once it exists.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    allowanceError != null -> Text(
                        allowanceError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    allowance == null -> CircularProgressIndicator(
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    allowance!!.isEmpty() -> Text(
                        "No provider accounts reported limits.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> allowance!!.forEach { account -> ProviderCard(account) }
                }

                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))

                Text(
                    "Tokens & cost (stored sessions)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                when {
                    summaryError != null -> Text(
                        summaryError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    summary == null -> CircularProgressIndicator(
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    else -> {
                        TotalsRow("Last 24 hours", summary!!.today)
                        Spacer(Modifier.height(10.dp))
                        TotalsRow("All listed sessions", summary!!.allListed)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Aggregated from the session rows the server returns; " +
                                "archived/pruned sessions beyond the list window aren't counted.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(account: AccountUsage) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                account.provider.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            account.plan?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        account.unavailableReason?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        account.windows.forEach { window ->
            Spacer(Modifier.height(6.dp))
            val used = window.usedPercent
            Row {
                Text(
                    window.label,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (used != null) {
                        "${(100 - used).coerceAtLeast(0.0).toInt()}% left"
                    } else {
                        window.detail ?: "unavailable"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        used == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        used >= 90 -> MaterialTheme.colorScheme.error
                        used >= 70 -> HermesColors.Caution
                        else -> HermesColors.Positive
                    },
                )
            }
            if (used != null) {
                Spacer(Modifier.height(3.dp))
                LinearProgressIndicator(
                    progress = { (used / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = when {
                        used >= 90 -> MaterialTheme.colorScheme.error
                        used >= 70 -> HermesColors.Caution
                        else -> MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceContainer,
                )
            }
            window.resetAtMs?.let {
                Text(
                    "resets ${formatRelativeTime(it)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        account.details.forEach {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatTokens(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.US, "%.1fk", value / 1_000.0)
    else -> value.toString()
}

@Composable
private fun TotalsRow(label: String, totals: TokenTotals) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            buildString {
                append(formatTokens(totals.inputTokens)).append(" in · ")
                append(formatTokens(totals.outputTokens)).append(" out · ")
                append(totals.sessionCount).append(" sessions")
                totals.estimatedCostUsd?.let {
                    append(" · ~$").append(String.format(Locale.US, "%.2f", it))
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
