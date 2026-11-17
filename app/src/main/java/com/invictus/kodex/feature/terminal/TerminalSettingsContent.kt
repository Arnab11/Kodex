package com.invictus.kodex.feature.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.invictus.kodex.R
import com.invictus.kodex.terminal.TerminalPrefs
import com.invictus.kodex.terminal.TerminalSchemes

/** Terminal options, shared by Settings -> Behavior and the sheet inside the terminal screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalSettingsContent(modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SwitchLine(stringResource(R.string.terminal_set_in_app), stringResource(R.string.terminal_set_in_app_sub), TerminalPrefs.useInApp, TerminalPrefs::updateUseInApp)

        Label(stringResource(R.string.terminal_set_scheme))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TerminalSchemes.all.forEach { (id, name) ->
                FilterChip(selected = TerminalPrefs.scheme == id, onClick = { TerminalPrefs.updateScheme(id) }, label = { Text(name) })
            }
        }

        Label(stringResource(R.string.terminal_set_font, TerminalPrefs.fontSp))
        Slider(
            value = TerminalPrefs.fontSp.toFloat(),
            onValueChange = { TerminalPrefs.updateFontSp(it.toInt()) },
            valueRange = TerminalPrefs.FONT_MIN.toFloat()..TerminalPrefs.FONT_MAX.toFloat(),
            steps = TerminalPrefs.FONT_MAX - TerminalPrefs.FONT_MIN - 1,
        )

        Label(stringResource(R.string.terminal_set_shell))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TerminalPrefs.SHELLS.forEach {
                FilterChip(selected = TerminalPrefs.shell == it, onClick = { TerminalPrefs.updateShell(it) }, label = { Text(it) })
            }
        }

        Label(stringResource(R.string.terminal_set_scrollback))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TerminalPrefs.SCROLLBACKS.forEach {
                FilterChip(selected = TerminalPrefs.scrollback == it, onClick = { TerminalPrefs.updateScrollback(it) }, label = { Text("$it") })
            }
        }

        Label(stringResource(R.string.terminal_set_tabs))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TerminalPrefs.TAB_LIMITS.forEach {
                FilterChip(selected = TerminalPrefs.maxTabs == it, onClick = { TerminalPrefs.updateMaxTabs(it) }, label = { Text("$it") })
            }
        }

        SwitchLine(stringResource(R.string.terminal_set_vibrate), null, TerminalPrefs.vibrate, TerminalPrefs::updateVibrate)
        SwitchLine(stringResource(R.string.terminal_set_wakelock), stringResource(R.string.terminal_set_wakelock_sub), TerminalPrefs.wakeLock, TerminalPrefs::updateWakeLock)
        Text(
            stringResource(R.string.terminal_set_new_tabs_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Label(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun SwitchLine(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
