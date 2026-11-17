package com.invictus.kodex.feature.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.invictus.kodex.R
import com.invictus.kodex.core.external.TermuxSetupPrefs
import com.invictus.kodex.feature.terminal.TerminalSettingsContent
import com.invictus.kodex.feature.workspace.clearRunHistory
import com.invictus.kodex.terminal.TerminalPrefs
import com.invictus.kodex.ui.icons.XIcons

/** Terminal: enable it, choose the in-app (Kodex) terminal or the Termux app, and tune the in-app one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTerminalScreen(
    onBack: () -> Unit,
    onOpenTermuxSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appContext = LocalContext.current.applicationContext
    TerminalPrefs.ensure(appContext)
    val terminalEnabled = TermuxSetupPrefs.enabled(appContext)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(R.string.terminal_title)) },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            SettingsSectionCard {
                SwitchSettingRow(
                    title = stringResource(R.string.termux_enable),
                    subtitle = stringResource(R.string.termux_enable_desc),
                    checked = terminalEnabled.value,
                    onCheckedChange = { TermuxSetupPrefs.setEnabled(appContext, it) },
                )
                if (terminalEnabled.value) {
                    SettingsDivider()
                    if (TerminalPrefs.engineReady) {
                        SwitchSettingRow(
                            title = stringResource(R.string.terminal_set_in_app),
                            subtitle = stringResource(R.string.terminal_set_in_app_sub),
                            checked = TerminalPrefs.useInApp,
                            onCheckedChange = TerminalPrefs::updateUseInApp,
                        )
                    } else {
                        // Not usable until Termux has python: the row leads to the setup instead of a switch.
                        ClickableSettingRow(
                            title = stringResource(R.string.terminal_set_in_app),
                            subtitle = stringResource(R.string.terminal_set_in_app_needs_setup),
                            chevron = XIcons.ChevronRight,
                            onClick = onOpenTermuxSetup,
                        )
                    }
                }
            }
            if (terminalEnabled.value) {
                SettingsSectionHeader(stringResource(R.string.terminal_set_run_group))
                SettingsSectionCard {
                    ClickableSettingRow(
                        title = stringResource(R.string.termux_run_clear_history),
                        subtitle = stringResource(R.string.terminal_set_run_history_desc),
                        chevron = XIcons.Delete,
                        onClick = {
                            clearRunHistory(appContext)
                            Toast.makeText(appContext, R.string.terminal_set_run_history_cleared, Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
            if (terminalEnabled.value && TerminalPrefs.inAppActive) {
                SettingsSectionHeader(stringResource(R.string.terminal_set_appearance))
                SettingsSectionCard { TerminalSettingsContent() }
            }
        }
    }
}
