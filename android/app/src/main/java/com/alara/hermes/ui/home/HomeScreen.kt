package com.alara.hermes.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.alara.hermes.ui.HomeUiState
import kotlinx.coroutines.launch

/**
 * Root adaptive layout:
 * - compact width (phone / Fold cover): single pane, list OR conversation
 * - expanded width (unfolded Fold / tablet / landscape): list + detail side by side
 * The hamburger drawer slides over either layout; it never displaces the
 * sessions + conversation panes.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun HomeScreen(
    viewModel: com.alara.hermes.ui.HomeViewModel,
    onOpenOverlay: (String) -> Unit,
    openSessionRequests: kotlinx.coroutines.flow.MutableStateFlow<String?>? = null,
) {
    val state by viewModel.state.collectAsState()
    val navigator = rememberListDetailPaneScaffoldNavigator<String>()
    val scope = rememberCoroutineScope()
    val snackbar = SnackbarHostState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissNotice()
        }
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    BackHandler(enabled = !drawerState.isOpen && navigator.canNavigateBack()) {
        scope.launch { navigator.navigateBack() }
    }

    // Back in the archived view returns to active conversations, not out of the app.
    BackHandler(enabled = !drawerState.isOpen && state.showArchived && !navigator.canNavigateBack()) {
        viewModel.toggleArchivedView()
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

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Session rows own horizontal swipes (pin/archive), so the drawer
        // opens from the hamburger only; swipe/scrim still close it.
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            HomeDrawer(
                state = state,
                onNavigate = { route ->
                    scope.launch { drawerState.close() }
                    onOpenOverlay(route)
                },
                onSwitchProfile = { profile ->
                    scope.launch { drawerState.close() }
                    viewModel.switchProfile(profile)
                },
            )
        },
    ) {
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
                            onPin = viewModel::setPinned,
                            onArchive = viewModel::setArchived,
                            onToggleArchivedView = viewModel::toggleArchivedView,
                            visibleSessions = viewModel.visibleSessions(state),
                            onRefresh = { viewModel.refreshSessions() },
                            onOpenMenu = { scope.launch { drawerState.open() } },
                            sourceOptions = viewModel.sourceFilterOptions(state),
                            onToggleSource = viewModel::toggleSourceFilter,
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
}

@Composable
private fun HomeDrawer(
    state: HomeUiState,
    onNavigate: (String) -> Unit,
    onSwitchProfile: (String) -> Unit,
) {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        drawerContentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Text(
            "Hermes",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        // Profile switcher: which backend profile (default, kimi, grok, …)
        // the session list and NEW conversations are scoped to.
        if (state.profiles.size > 1) {
            Text(
                "Profile",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
            )
            state.profiles.forEach { profile ->
                NavigationDrawerItem(
                    label = { Text(profile.displayName) },
                    icon = {
                        Icon(
                            if (profile.id == state.activeProfile) {
                                Icons.Filled.Person
                            } else {
                                Icons.Outlined.PersonOutline
                            },
                            contentDescription = null,
                        )
                    },
                    badge = if (profile.isDefault) {
                        { Text("default", style = MaterialTheme.typography.labelSmall) }
                    } else {
                        null
                    },
                    selected = profile.id == state.activeProfile,
                    onClick = { onSwitchProfile(profile.id) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        } else {
            // No named profiles configured yet: say where to add them instead
            // of silently hiding the switcher.
            Text(
                "Profile",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
            )
            Text(
                "Add your desktop profile names (kimi, grok, …) under " +
                    "Settings → Connection → Profiles to switch between them here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp),
            )
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Spacer(Modifier.height(12.dp))
        if (state.features.skills) {
            DrawerItem("Skills", Icons.Outlined.AutoAwesome) { onNavigate("skills") }
        }
        if (state.features.automations) {
            DrawerItem("Automations", Icons.Outlined.Schedule) { onNavigate("automations") }
        }
        DrawerItem("Usage", Icons.Outlined.DataUsage) { onNavigate("usage") }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        DrawerItem("Settings", Icons.Filled.Settings) { onNavigate("settings") }
    }
}

@Composable
private fun DrawerItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label) },
        icon = { Icon(icon, contentDescription = null) },
        selected = false,
        onClick = onClick,
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}
