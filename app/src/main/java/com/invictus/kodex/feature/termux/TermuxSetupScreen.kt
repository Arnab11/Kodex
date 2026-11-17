package com.invictus.kodex.feature.termux

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.invictus.kodex.R
import com.invictus.kodex.core.external.TermuxLauncher
import com.invictus.kodex.core.external.TermuxSetupModel
import com.invictus.kodex.feature.settings.SettingsDivider
import com.invictus.kodex.feature.settings.SettingsSectionCard
import com.invictus.kodex.feature.settings.SettingsSectionHeader
import com.invictus.kodex.feature.settings.SwitchSettingRow
import com.invictus.kodex.ui.icons.XIcons

/**
 * One-time Termux setup: live connection checks, what to install (preset, chips, mirror, git
 * identity), then the install itself runs in a visible Termux session.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TermuxSetupScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TermuxSetupViewModel = viewModel(),
) {
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refresh()
    }

    val probe = viewModel.probe
    val sel = viewModel.selection
    val connected = viewModel.termuxInstalled && viewModel.permissionGranted && probe?.externalAllowed == true
    val installedPkgs = probe?.installed.orEmpty()
    val presetId = TermuxSetupModel.presets.firstOrNull { it.packages == sel.packages }?.id
        ?: TermuxSetupModel.PRESET_CUSTOM

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(R.string.termux_screen_title)) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                stringResource(R.string.termux_screen_intro),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 8.dp),
            )

            // ---- 1 · Connection ----
            SettingsSectionHeader(stringResource(R.string.termux_sec_connection))
            SettingsSectionCard {
                StatusRow(
                    label = stringResource(R.string.termux_chk_installed),
                    ok = viewModel.termuxInstalled,
                    actionLabel = stringResource(R.string.termux_get),
                    onAction = { TermuxLauncher.openReleasesPage(context) },
                )
                SettingsDivider()
                StatusRow(
                    label = stringResource(R.string.termux_chk_permission),
                    ok = viewModel.permissionGranted,
                    actionLabel = stringResource(R.string.termux_allow),
                    onAction = { permissionLauncher.launch(TermuxLauncher.PERMISSION) },
                    actionEnabled = viewModel.termuxInstalled,
                )
                SettingsDivider()
                StatusRow(
                    label = stringResource(R.string.termux_chk_external),
                    ok = probe?.externalAllowed == true,
                    busy = viewModel.checking,
                    actionLabel = stringResource(R.string.termux_copy_open_cmd),
                    onAction = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("termux-bootstrap", TermuxSetupModel.BOOTSTRAP_COMMAND))
                        viewModel.arm()
                        TermuxLauncher.launch(context)
                    },
                    actionEnabled = viewModel.permissionGranted,
                    hint = stringResource(R.string.termux_external_hint),
                )
                SettingsDivider()
                StatusRow(
                    label = stringResource(R.string.termux_chk_storage),
                    ok = probe?.storageOk == true,
                    hint = stringResource(R.string.termux_storage_hint),
                )
                SettingsDivider()
                StatusRow(
                    label = stringResource(R.string.termux_chk_engine),
                    ok = TermuxSetupModel.TERMINAL_ENGINE_PACKAGE in installedPkgs,
                    busy = viewModel.checking,
                    hint = stringResource(R.string.termux_engine_hint),
                )
                SettingsDivider()
                StatusRow(
                    label = stringResource(R.string.termux_chk_overlay),
                    ok = viewModel.overlayAck,
                    actionLabel = stringResource(R.string.termux_open_settings),
                    onAction = {
                        TermuxLauncher.openOverlaySettings(context)
                        viewModel.acknowledgeOverlay()
                    },
                    actionEnabled = viewModel.termuxInstalled,
                    hint = stringResource(R.string.termux_overlay_hint),
                )
            }
            TextButton(onClick = { viewModel.refresh(userInitiated = true) }, modifier = Modifier.padding(start = 8.dp)) {
                Text(stringResource(R.string.termux_recheck))
            }

            // ---- 2 · What to install ----
            SettingsSectionHeader(stringResource(R.string.termux_sec_install))
            SettingsSectionCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                DropdownField(
                    label = stringResource(R.string.termux_preset),
                    value = TermuxSetupModel.presets.firstOrNull { it.id == presetId }?.label
                        ?: stringResource(R.string.termux_preset_custom),
                    options = TermuxSetupModel.presets.map { it.id to it.label },
                    onSelect = { id -> TermuxSetupModel.presets.firstOrNull { it.id == id }?.let(viewModel::applyPreset) },
                )
                TermuxSetupModel.groups.forEach { group ->
                    Text(
                        stringResource(group.titleRes),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        group.packages.forEach { pkg ->
                            val isInstalled = pkg.name in installedPkgs
                            FilterChip(
                                selected = pkg.name in sel.packages,
                                onClick = { viewModel.togglePackage(pkg.name) },
                                label = { Text(if (pkg.large) pkg.name + " · " + stringResource(R.string.termux_large) else pkg.name) },
                                leadingIcon = if (isInstalled) {
                                    { Icon(XIcons.Check, contentDescription = stringResource(R.string.termux_installed), modifier = Modifier.size(16.dp)) }
                                } else null,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = sel.extra,
                    onValueChange = { viewModel.update(sel.copy(extra = it)) },
                    label = { Text(stringResource(R.string.termux_extra)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
                DropdownField(
                    label = stringResource(R.string.termux_mirror),
                    value = (TermuxSetupModel.mirrors.firstOrNull { it.id == sel.mirrorId } ?: TermuxSetupModel.mirrors.first()).let { m ->
                        if (m.url == null) m.label + (probe?.mirrorHost?.let { " ($it)" } ?: "") else m.label
                    },
                    options = TermuxSetupModel.mirrors.map { it.id to it.label },
                    onSelect = { viewModel.update(sel.copy(mirrorId = it)) },
                    modifier = Modifier.padding(top = 16.dp),
                )
                OutlinedTextField(
                    value = sel.gitName,
                    onValueChange = { viewModel.update(sel.copy(gitName = it)) },
                    label = { Text(stringResource(R.string.termux_git_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
                OutlinedTextField(
                    value = sel.gitEmail,
                    onValueChange = { viewModel.update(sel.copy(gitEmail = it)) },
                    label = { Text(stringResource(R.string.termux_git_email)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            SettingsSectionCard(modifier = Modifier.padding(top = 12.dp)) {
                SwitchSettingRow(
                    title = stringResource(R.string.termux_opt_storage),
                    subtitle = stringResource(R.string.termux_opt_storage_desc),
                    checked = sel.askStorage,
                    onCheckedChange = { viewModel.update(sel.copy(askStorage = it)) },
                )
                SettingsDivider()
                SwitchSettingRow(
                    title = stringResource(R.string.termux_opt_upgrade),
                    subtitle = stringResource(R.string.termux_opt_upgrade_desc),
                    checked = sel.upgrade,
                    onCheckedChange = { viewModel.update(sel.copy(upgrade = it)) },
                )
            }

            // ---- 3 · Install ----
            SettingsSectionHeader(stringResource(R.string.termux_sec_run))
            Button(
                onClick = {
                    if (!viewModel.startInstall()) {
                        Toast.makeText(context, R.string.termux_start_failed, Toast.LENGTH_LONG).show()
                    }
                },
                enabled = connected,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            ) { Text(stringResource(R.string.termux_start)) }
            if (!connected) {
                Text(
                    stringResource(R.string.termux_start_blocked),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
            }
            if (viewModel.installStarted) {
                Text(
                    stringResource(R.string.termux_installing_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
                viewModel.verify?.let { results ->
                    SettingsSectionCard {
                        results.forEachIndexed { i, (name, ok) ->
                            if (i > 0) SettingsDivider()
                            StatusRow(label = name, ok = ok)
                        }
                    }
                }
                Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.refresh(userInitiated = true) }) { Text(stringResource(R.string.termux_verify)) }
                    Button(onClick = { viewModel.finish(); onBack() }) { Text(stringResource(R.string.termux_finish)) }
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 32.dp))
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    ok: Boolean,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    actionEnabled: Boolean = true,
    hint: String? = null,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                ok -> Icon(XIcons.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                busy -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                else -> Icon(XIcons.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            }
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp).weight(1f))
            if (actionLabel != null && !ok) {
                TextButton(onClick = onAction, enabled = actionEnabled) { Text(actionLabel) }
            }
        }
        if (hint != null && !ok) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 32.dp),
            )
        }
    }
}

@Composable
private fun DropdownField(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(value, modifier = Modifier.weight(1f), maxLines = 1)
                Icon(XIcons.ExpandMore, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (id, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onSelect(id) })
                }
            }
        }
    }
}
