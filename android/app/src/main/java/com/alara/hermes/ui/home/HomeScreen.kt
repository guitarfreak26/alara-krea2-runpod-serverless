package com.alara.hermes.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/**
 * Root adaptive layout:
 * - compact width (phone / Fold cover): single pane, list OR conversation
 * - expanded width (unfolded Fold / tablet / landscape): list + detail side by side
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun HomeScreen(
    viewModel: com.alara.hermes.ui.HomeViewModel,
    onOpenSettings: () -> Unit,
    openSessionRequests: kotlinx.coroutines.flow.MutableStateFlow<String?>? = null,
) {
    val state by viewModel.state.collectAsState()
    val navigator = rememberListDetailPaneScaffoldNavigator<String>()
    val scope = rememberCoroutineScope()
    val snackbar = SnackbarHostState()

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissNotice()
        }
    }

    BackHandler(enabled = navigator.canNavigateBack()) {
        scope.launch { navigator.navigateBack() }
    }

    // Notification tap -> open that conversation.
    LaunchedEffect(openSessionRequests) {
        openSessionRequests?.collect { key ->
            if (key != null) {
                viewModel.openSession(key)
                navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, key)
                openSessionRequests.value = null
            }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        NavigableListDetailPaneScaffold(
            navigator = navigator,
            listPane = {
                AnimatedPane {
                    SessionListPane(
                        state = state,
                        onOpenSession = { key ->
                            viewModel.openSession(key)
                            scope.launch {
                                navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, key ?: "new")
                            }
                        },
                        onSwitchProfile = viewModel::switchProfile,
                        onSearch = viewModel::setSearchQuery,
                        onRename = viewModel::renameSession,
                        onDelete = viewModel::deleteSession,
                        onRefresh = { viewModel.refreshSessions() },
                        onOpenSettings = onOpenSettings,
                    )
                }
            },
            detailPane = {
                AnimatedPane {
                    ChatPane(
                        state = state,
                        viewModel = viewModel,
                        onBack = if (navigator.canNavigateBack()) {
                            { scope.launch { navigator.navigateBack() } }
                        } else {
                            null
                        },
                    )
                }
            },
        )
    }
}
