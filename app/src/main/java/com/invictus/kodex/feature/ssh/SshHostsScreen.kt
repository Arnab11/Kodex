package com.invictus.kodex.feature.ssh

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.invictus.kodex.R
import com.invictus.kodex.core.ssh.SshAuthType
import com.invictus.kodex.core.ssh.SshConnectionState
import com.invictus.kodex.core.ssh.SshCredentialPrompt
import com.invictus.kodex.core.ssh.SshHostConfig
import com.invictus.kodex.core.ssh.SshHostKeyPrompt
import com.invictus.kodex.core.ssh.SshQuickConnectParser
import com.invictus.kodex.ui.icons.XIcons
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshHostsSheetContent(
    onBack: () -> Unit,
    onOpenWorkspace: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    viewModel: SshViewModel = viewModel(factory = SshViewModel.Factory),
) {
    val hosts by viewModel.hosts.collectAsStateWithLifecycle()
    val detectedHosts by viewModel.detectedHosts.collectAsStateWithLifecycle()
    val connState by viewModel.connectionState.collectAsStateWithLifecycle()
    val hostKeyPrompt by viewModel.hostKeyPrompt.collectAsStateWithLifecycle()
    val credentialPrompt by viewModel.credentialPrompt.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    val remoteBrowser by viewModel.remoteBrowser.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var quickInput by rememberSaveable { mutableStateOf("") }
    var editingHost by remember { mutableStateOf<SshHostConfig?>(null) }
    var showForm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshDetectedHosts()
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                SshEffect.OpenWorkspace -> onOpenWorkspace()
                is SshEffect.Message -> scope.launch { snackbarHostState.showSnackbar(effect.text) }
            }
        }
    }

    val startQuickConnect: () -> Unit = {
        val parsed = SshQuickConnectParser.parse(quickInput)
        if (parsed == null) {
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.ssh_err_invalid_quick))
            }
        } else {
            val existing = hosts.firstOrNull {
                it.host.equals(parsed.host, ignoreCase = true) &&
                    it.port == parsed.port &&
                    it.username == parsed.username
            }
            if (existing != null) {
                viewModel.connectAndOpenWorkspace(existing)
            } else {
                editingHost = parsed
                viewModel.clearTestResult()
                showForm = true
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.ssh_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.ssh_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            androidx.compose.material3.FilledTonalIconButton(
                onClick = {
                    editingHost = SshHostConfig()
                    viewModel.clearTestResult()
                    showForm = true
                },
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
            ) {
                Icon(XIcons.Add, contentDescription = stringResource(R.string.ssh_add_host))
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val state = connState) {
                is SshConnectionState.Connecting -> {
                    item(key = "connecting") {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            ),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = stringResource(R.string.ssh_connecting, state.host.displayName),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { viewModel.cancelConnect() }) {
                                        Text(stringResource(R.string.action_cancel))
                                    }
                                }
                                Text(
                                    text = state.step,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                is SshConnectionState.Connected -> {
                    item(key = "connected") {
                        ActiveSshCard(
                            state = state,
                            onOpenWorkspace = onOpenWorkspace,
                            onBrowseFolder = { viewModel.openRemoteBrowser() },
                            onOpenTerminal = { viewModel.openSshTerminal(context, state.host, state.remoteRoot) },
                            onDisconnect = { viewModel.disconnect() },
                        )
                    }
                }
                else -> Unit
            }

            item(key = "quick_connect") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = quickInput,
                        onValueChange = { quickInput = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.ssh_quick_connect_hint)) },
                        leadingIcon = { Icon(XIcons.SshHost, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { startQuickConnect() }),
                        shape = RoundedCornerShape(14.dp),
                    )
                    Button(
                        onClick = startQuickConnect,
                        enabled = quickInput.isNotBlank() && connState !is SshConnectionState.Connecting,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.height(52.dp),
                    ) {
                        Text(stringResource(R.string.ssh_quick_connect_action))
                    }
                }
            }

            item(key = "saved_header") {
                Text(
                    text = stringResource(R.string.ssh_saved_hosts),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (hosts.isEmpty() && detectedHosts.isEmpty()) {
                item(key = "empty") {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        ),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                XIcons.SshHost,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.ssh_no_hosts),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = stringResource(R.string.ssh_no_hosts_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                items(items = hosts, key = { it.id }) { host ->
                    SshHostCard(
                        host = host,
                        isConnecting = (connState as? SshConnectionState.Connecting)?.host?.id == host.id,
                        onConnectWorkspace = { viewModel.connectAndOpenWorkspace(host) },
                        onOpenTerminal = { viewModel.openSshTerminal(context, host) },
                        onEdit = {
                            editingHost = host
                            viewModel.clearTestResult()
                            showForm = true
                        },
                        onDelete = { viewModel.deleteHost(host.id) },
                    )
                }
            }

            if (detectedHosts.isNotEmpty()) {
                item(key = "detected_header") {
                    Text(
                        text = stringResource(R.string.ssh_detected_hosts),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(items = detectedHosts, key = { it.id }) { host ->
                    SshHostCard(
                        host = host,
                        isConnecting = (connState as? SshConnectionState.Connecting)?.host?.id == host.id,
                        onConnectWorkspace = { viewModel.connectAndOpenWorkspace(host) },
                        onOpenTerminal = { viewModel.openSshTerminal(context, host) },
                        onEdit = {
                            editingHost = host.copy(id = java.util.UUID.randomUUID().toString())
                            viewModel.clearTestResult()
                            showForm = true
                        },
                        onDelete = null,
                    )
                }
            }

            item(key = "bottom_spacer") {
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showForm && editingHost != null) {
        SshHostFormSheet(
            initial = editingHost!!,
            testing = testing,
            testResult = testResult,
            onTest = { viewModel.testConnection(it) },
            onSave = { config ->
                viewModel.saveHost(config)
                showForm = false
            },
            onSaveAndConnect = { config ->
                viewModel.saveHost(config)
                showForm = false
                viewModel.connectAndOpenWorkspace(config)
            },
            onDismiss = { showForm = false },
        )
    }

    remoteBrowser?.let { browser ->
        RemoteFolderBrowserSheet(
            state = browser,
            onNavigate = { viewModel.navigateRemoteBrowser(it) },
            onUp = { viewModel.navigateRemoteBrowserUp() },
            onSelectCurrent = { viewModel.selectRemoteBrowserFolder() },
            onDismiss = { viewModel.closeRemoteBrowser() },
        )
    }

    hostKeyPrompt?.let { prompt ->
        HostKeyVerificationDialog(
            prompt = prompt,
            onTrust = { viewModel.respondHostKey(true) },
            onReject = { viewModel.respondHostKey(false) },
        )
    }

    credentialPrompt?.let { prompt ->
        CredentialPromptDialog(
            prompt = prompt,
            onSubmit = { viewModel.respondCredential(it) },
            onCancel = { viewModel.respondCredential(null) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshHostsScreen(
    onBack: () -> Unit,
    onOpenWorkspace: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SshViewModel = viewModel(factory = SshViewModel.Factory),
) {
    val hosts by viewModel.hosts.collectAsStateWithLifecycle()
    val detectedHosts by viewModel.detectedHosts.collectAsStateWithLifecycle()
    val connState by viewModel.connectionState.collectAsStateWithLifecycle()
    val hostKeyPrompt by viewModel.hostKeyPrompt.collectAsStateWithLifecycle()
    val credentialPrompt by viewModel.credentialPrompt.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    val remoteBrowser by viewModel.remoteBrowser.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var quickInput by rememberSaveable { mutableStateOf("") }
    var editingHost by remember { mutableStateOf<SshHostConfig?>(null) }
    var showForm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshDetectedHosts()
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                SshEffect.OpenWorkspace -> onOpenWorkspace()
                is SshEffect.Message -> scope.launch { snackbarHostState.showSnackbar(effect.text) }
            }
        }
    }

    val startQuickConnect: () -> Unit = {
        val parsed = SshQuickConnectParser.parse(quickInput)
        if (parsed == null) {
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.ssh_err_invalid_quick))
            }
        } else {
            // If a saved host matches host+port+user, use its saved credentials/config; otherwise open form pre-filled.
            val existing = hosts.firstOrNull {
                it.host.equals(parsed.host, ignoreCase = true) &&
                    it.port == parsed.port &&
                    it.username == parsed.username
            }
            if (existing != null) {
                viewModel.connectAndOpenWorkspace(existing)
            } else {
                editingHost = parsed
                viewModel.clearTestResult()
                showForm = true
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.ssh_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = stringResource(R.string.ssh_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editingHost = SshHostConfig()
                    viewModel.clearTestResult()
                    showForm = true
                },
            ) {
                Icon(XIcons.Add, contentDescription = stringResource(R.string.ssh_add_host))
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            // Active connection card (if connected or connecting)
            when (val state = connState) {
                is SshConnectionState.Connecting -> {
                    item(key = "connecting") {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            ),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = stringResource(R.string.ssh_connecting, state.host.displayName),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { viewModel.cancelConnect() }) {
                                        Text(stringResource(R.string.action_cancel))
                                    }
                                }
                                Text(
                                    text = state.step,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                is SshConnectionState.Connected -> {
                    item(key = "connected") {
                        ActiveSshCard(
                            state = state,
                            onOpenWorkspace = onOpenWorkspace,
                            onBrowseFolder = { viewModel.openRemoteBrowser() },
                            onOpenTerminal = { viewModel.openSshTerminal(context, state.host, state.remoteRoot) },
                            onDisconnect = { viewModel.disconnect() },
                        )
                    }
                }
                else -> Unit
            }

            // Quick Connect bar (VS Code style)
            item(key = "quick_connect") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = quickInput,
                        onValueChange = { quickInput = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.ssh_quick_connect_hint)) },
                        leadingIcon = { Icon(XIcons.SshHost, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { startQuickConnect() }),
                        shape = RoundedCornerShape(14.dp),
                    )
                    Button(
                        onClick = startQuickConnect,
                        enabled = quickInput.isNotBlank() && connState !is SshConnectionState.Connecting,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.height(52.dp),
                    ) {
                        Text(stringResource(R.string.ssh_quick_connect_action))
                    }
                }
            }

            item(key = "saved_header") {
                Text(
                    text = stringResource(R.string.ssh_saved_hosts),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (hosts.isEmpty() && detectedHosts.isEmpty()) {
                item(key = "empty") {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        ),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                XIcons.SshHost,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.ssh_no_hosts),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = stringResource(R.string.ssh_no_hosts_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                items(items = hosts, key = { it.id }) { host ->
                    SshHostCard(
                        host = host,
                        isConnecting = (connState as? SshConnectionState.Connecting)?.host?.id == host.id,
                        onConnectWorkspace = { viewModel.connectAndOpenWorkspace(host) },
                        onOpenTerminal = { viewModel.openSshTerminal(context, host) },
                        onEdit = {
                            editingHost = host
                            viewModel.clearTestResult()
                            showForm = true
                        },
                        onDelete = { viewModel.deleteHost(host.id) },
                    )
                }
            }

            if (detectedHosts.isNotEmpty()) {
                item(key = "detected_header") {
                    Text(
                        text = stringResource(R.string.ssh_detected_hosts),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(items = detectedHosts, key = { it.id }) { host ->
                    SshHostCard(
                        host = host,
                        isConnecting = (connState as? SshConnectionState.Connecting)?.host?.id == host.id,
                        onConnectWorkspace = { viewModel.connectAndOpenWorkspace(host) },
                        onOpenTerminal = { viewModel.openSshTerminal(context, host) },
                        onEdit = {
                            editingHost = host.copy(id = java.util.UUID.randomUUID().toString())
                            viewModel.clearTestResult()
                            showForm = true
                        },
                        onDelete = null,
                    )
                }
            }

            item(key = "bottom_spacer") {
                Spacer(Modifier.height(72.dp))
            }
        }
        }
    }

    if (showForm && editingHost != null) {
        SshHostFormSheet(
            initial = editingHost!!,
            testing = testing,
            testResult = testResult,
            onTest = { viewModel.testConnection(it) },
            onSave = { config ->
                viewModel.saveHost(config)
                showForm = false
            },
            onSaveAndConnect = { config ->
                viewModel.saveHost(config)
                showForm = false
                viewModel.connectAndOpenWorkspace(config)
            },
            onDismiss = { showForm = false },
        )
    }

    remoteBrowser?.let { browser ->
        RemoteFolderBrowserSheet(
            state = browser,
            onNavigate = { viewModel.navigateRemoteBrowser(it) },
            onUp = { viewModel.navigateRemoteBrowserUp() },
            onSelectCurrent = { viewModel.selectRemoteBrowserFolder() },
            onDismiss = { viewModel.closeRemoteBrowser() },
        )
    }

    hostKeyPrompt?.let { prompt ->
        HostKeyVerificationDialog(
            prompt = prompt,
            onTrust = { viewModel.respondHostKey(true) },
            onReject = { viewModel.respondHostKey(false) },
        )
    }

    credentialPrompt?.let { prompt ->
        CredentialPromptDialog(
            prompt = prompt,
            onSubmit = { viewModel.respondCredential(it) },
            onCancel = { viewModel.respondCredential(null) },
        )
    }
}

@Composable
private fun ActiveSshCard(
    state: SshConnectionState.Connected,
    onOpenWorkspace: () -> Unit,
    onBrowseFolder: () -> Unit,
    onOpenTerminal: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = state.host.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${state.host.endpoint} • ${state.remoteRoot}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onDisconnect) {
                    Text(stringResource(R.string.ssh_disconnect))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onOpenWorkspace,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(XIcons.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ssh_connect_workspace), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = onBrowseFolder,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(XIcons.Folder, contentDescription = stringResource(R.string.ssh_browse_remote), modifier = Modifier.size(18.dp))
                }
                OutlinedButton(
                    onClick = onOpenTerminal,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(XIcons.Terminal, contentDescription = stringResource(R.string.ssh_open_terminal), modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun SshHostCard(
    host: SshHostConfig,
    isConnecting: Boolean,
    onConnectWorkspace: () -> Unit,
    onOpenTerminal: () -> Unit,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = !isConnecting, onClick = onConnectWorkspace),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                modifier = Modifier.size(42.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isConnecting) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            XIcons.SshHost,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = host.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(host.endpoint)
                        if (host.defaultRemotePath.isNotBlank()) {
                            append(" • ")
                            append(host.defaultRemotePath)
                        }
                        if (host.proxyJump.isNotBlank()) {
                            append(" • via ")
                            append(host.proxyJump)
                        }
                        if (host.portForwards.isNotEmpty()) {
                            append(" • ")
                            append(host.portForwards.size)
                            append(if (host.portForwards.size == 1) " tunnel" else " tunnels")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onOpenTerminal) {
                Icon(XIcons.Terminal, contentDescription = stringResource(R.string.ssh_open_terminal))
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(XIcons.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ssh_connect_workspace)) },
                        leadingIcon = { Icon(XIcons.FolderOpen, contentDescription = null) },
                        onClick = { menuOpen = false; onConnectWorkspace() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ssh_open_terminal)) },
                        leadingIcon = { Icon(XIcons.Terminal, contentDescription = null) },
                        onClick = { menuOpen = false; onOpenTerminal() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ssh_edit_host)) },
                        leadingIcon = { Icon(XIcons.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                    if (onDelete != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete)) },
                            leadingIcon = { Icon(XIcons.Delete, contentDescription = null) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SshHostFormSheet(
    initial: SshHostConfig,
    testing: Boolean,
    testResult: SshTestResult?,
    onTest: (SshHostConfig) -> Unit,
    onSave: (SshHostConfig) -> Unit,
    onSaveAndConnect: (SshHostConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var label by remember(initial.id) { mutableStateOf(initial.label) }
    var host by remember(initial.id) { mutableStateOf(initial.host) }
    var portText by remember(initial.id) { mutableStateOf(initial.port.toString()) }
    var username by remember(initial.id) { mutableStateOf(initial.username) }
    var authType by remember(initial.id) { mutableStateOf(initial.authType) }
    var password by remember(initial.id) { mutableStateOf(initial.password) }
    var showPassword by remember { mutableStateOf(false) }
    var privateKey by remember(initial.id) { mutableStateOf(initial.privateKey) }
    var passphrase by remember(initial.id) { mutableStateOf(initial.passphrase) }
    var remotePath by remember(initial.id) { mutableStateOf(initial.defaultRemotePath) }
    var tunnelingEnabled by remember(initial.id) {
        mutableStateOf(initial.proxyJump.isNotBlank() || initial.portForwards.isNotEmpty())
    }
    var proxyJump by remember(initial.id) { mutableStateOf(initial.proxyJump) }
    var portForwardsText by remember(initial.id) {
        mutableStateOf(initial.portForwards.joinToString(", ") { it.toDisplayString() })
    }
    var rememberCreds by remember(initial.id) { mutableStateOf(initial.rememberCredentials) }

    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()?.let { content ->
                privateKey = content
            }
        }
    }

    val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: SshHostConfig.DEFAULT_PORT
    val valid = host.isNotBlank() && username.isNotBlank()

    fun buildConfig(): SshHostConfig = initial.copy(
        label = label.trim(),
        host = host.trim(),
        port = port,
        username = username.trim(),
        authType = authType,
        password = password,
        privateKey = privateKey,
        passphrase = passphrase,
        defaultRemotePath = remotePath.trim(),
        proxyJump = if (tunnelingEnabled) proxyJump.trim() else "",
        portForwards = if (tunnelingEnabled) com.invictus.kodex.core.ssh.SshConfigParser.parseForwardList(portForwardsText) else emptyList(),
        rememberCredentials = rememberCreds,
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(if (initial.host.isBlank()) R.string.ssh_add_host else R.string.ssh_edit_host),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.ssh_label_optional)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.ssh_host)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter { ch -> ch.isDigit() }.take(5) },
                    label = { Text(stringResource(R.string.ssh_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(96.dp),
                )
            }

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.ssh_username)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = authType == SshAuthType.PASSWORD,
                    onClick = { authType = SshAuthType.PASSWORD },
                    label = { Text(stringResource(R.string.ssh_auth_password)) },
                )
                FilterChip(
                    selected = authType == SshAuthType.PRIVATE_KEY,
                    onClick = { authType = SshAuthType.PRIVATE_KEY },
                    leadingIcon = { Icon(XIcons.Key, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text(stringResource(R.string.ssh_auth_key)) },
                )
            }

            if (authType == SshAuthType.PASSWORD) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.ssh_password_label)) },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) XIcons.VisibilityOff else XIcons.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = privateKey,
                    onValueChange = { privateKey = it },
                    label = { Text(stringResource(R.string.ssh_private_key_label)) },
                    minLines = 3,
                    maxLines = 6,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { keyPicker.launch(arrayOf("*/*")) },
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(XIcons.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ssh_import_key))
                }
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.ssh_passphrase_optional)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OutlinedTextField(
                value = remotePath,
                onValueChange = { remotePath = it },
                label = { Text(stringResource(R.string.ssh_remote_path_optional)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.ssh_tunneling_toggle),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = tunnelingEnabled, onCheckedChange = { tunnelingEnabled = it })
            }

            if (tunnelingEnabled) {
                OutlinedTextField(
                    value = proxyJump,
                    onValueChange = { proxyJump = it },
                    label = { Text(stringResource(R.string.ssh_proxy_jump_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = portForwardsText,
                    onValueChange = { portForwardsText = it },
                    label = { Text(stringResource(R.string.ssh_port_forwards_optional)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.ssh_remember_creds),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = rememberCreds, onCheckedChange = { rememberCreds = it })
            }

            if (testResult != null) {
                Surface(
                    color = if (testResult.ok) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = testResult.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (testResult.ok) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onTest(buildConfig()) },
                    enabled = valid && !testing,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(stringResource(if (testing) R.string.ssh_testing else R.string.ssh_test_connection))
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { onSave(buildConfig()) },
                    enabled = valid,
                ) {
                    Text(stringResource(R.string.ssh_save))
                }
                Button(
                    onClick = { onSaveAndConnect(buildConfig()) },
                    enabled = valid,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(stringResource(R.string.ssh_save_connect))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteFolderBrowserSheet(
    state: RemoteBrowserState,
    onNavigate: (String) -> Unit,
    onUp: () -> Unit,
    onSelectCurrent: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onUp, enabled = state.currentPath != "/") {
                    Icon(XIcons.ArrowUpward, contentDescription = "Up")
                }
                Text(
                    text = state.currentPath,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onSelectCurrent, shape = RoundedCornerShape(10.dp)) {
                    Text(stringResource(R.string.ssh_open_this_folder))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            if (state.loading) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.error != null) {
                Text(
                    text = state.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(items = state.entries, key = { it.remotePath }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onNavigate(entry.remotePath) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(XIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = entry.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HostKeyVerificationDialog(
    prompt: SshHostKeyPrompt,
    onTrust: () -> Unit,
    onReject: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = {
            Text(
                stringResource(
                    if (prompt.isMismatch) R.string.ssh_hostkey_mismatch_title else R.string.ssh_hostkey_title,
                ),
            )
        },
        text = {
            Text(
                stringResource(
                    if (prompt.isMismatch) R.string.ssh_hostkey_mismatch_body else R.string.ssh_hostkey_body,
                    prompt.host,
                    prompt.port,
                    prompt.keyType,
                    prompt.fingerprint,
                ),
            )
        },
        confirmButton = {
            Button(onClick = onTrust) {
                Text(stringResource(R.string.ssh_hostkey_trust))
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun CredentialPromptDialog(
    prompt: SshCredentialPrompt,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var secret by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.ssh_cred_prompt_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(prompt.promptLabel, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSubmit(secret) }, enabled = secret.isNotEmpty()) {
                Text(stringResource(R.string.ssh_quick_connect_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
