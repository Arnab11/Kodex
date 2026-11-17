package com.invictus.kodex.feature.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.invictus.kodex.R
import com.invictus.kodex.ui.icons.XIcons
import java.io.File

/**
 * Run a command in the terminal: type it (or tap a pinned / suggested / recent one) and run it in the
 * current tab, or in a new tab opened in [dir]. Output is the live terminal, so nothing is captured here.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TerminalRunSheet(
    dir: File,
    canRunHere: Boolean,
    onRun: (command: String, inNewTab: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    var command by rememberSaveable { mutableStateOf("") }
    var newTab by rememberSaveable { mutableStateOf(!canRunHere) }
    var history by remember { mutableStateOf(RunStore.history(context)) }
    var pins by remember { mutableStateOf(RunStore.pins(context)) }
    val suggestions = remember(dir) { RunStore.suggestionsFor(dir) }
    val focus = remember { FocusRequester() }
    val inNewTab = newTab || !canRunHere

    fun run(raw: String) {
        val c = raw.trim()
        if (c.isEmpty()) return
        RunStore.record(context, c)
        onRun(c, inNewTab)
        onDismiss()
    }

    LaunchedEffect(Unit) {
        try { focus.requestFocus() } catch (_: IllegalStateException) {}
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.terminal_run_title), style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = { Text(stringResource(R.string.terminal_run_hint)) },
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                minLines = 1,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { run(command) }),
                trailingIcon = {
                    if (command.isNotEmpty()) {
                        IconButton(onClick = { command = "" }) { Icon(XIcons.Close, contentDescription = null) }
                    }
                },
            )

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !inNewTab,
                    onClick = { newTab = false },
                    enabled = canRunHere,
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text(stringResource(R.string.terminal_run_here)) }
                SegmentedButton(
                    selected = inNewTab,
                    onClick = { newTab = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text(stringResource(R.string.terminal_run_new)) }
            }
            Text(
                if (inNewTab) stringResource(R.string.terminal_run_in_new, dir.absolutePath)
                else stringResource(R.string.terminal_run_in_here),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Button(onClick = { run(command) }, enabled = command.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Icon(XIcons.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.terminal_run_go))
            }

            if (pins.isNotEmpty()) {
                SectionLabel(stringResource(R.string.terminal_run_pinned))
                pins.forEach { cmd ->
                    CommandRow(
                        command = cmd,
                        pinned = true,
                        onFill = { command = cmd },
                        onRun = { run(cmd) },
                        onPin = { RunStore.setPinned(context, cmd, false); pins = RunStore.pins(context) },
                    )
                }
            }

            if (suggestions.isNotEmpty()) {
                SectionLabel(stringResource(R.string.terminal_run_suggested))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { s ->
                        AssistChip(
                            onClick = { command = s },
                            label = { Text(s, fontFamily = FontFamily.Monospace, maxLines = 1) },
                        )
                    }
                }
            }

            val recent = history.filter { it !in pins }.take(8)
            if (recent.isNotEmpty()) {
                SectionLabel(stringResource(R.string.terminal_run_recent)) {
                    TextButton(onClick = { RunStore.clearHistory(context); history = emptyList() }) {
                        Text(stringResource(R.string.termux_run_clear_history))
                    }
                }
                recent.forEach { cmd ->
                    CommandRow(
                        command = cmd,
                        pinned = false,
                        onFill = { command = cmd },
                        onRun = { run(cmd) },
                        onPin = { RunStore.setPinned(context, cmd, true); pins = RunStore.pins(context) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        action?.invoke()
    }
}

@Composable
private fun CommandRow(command: String, pinned: Boolean, onFill: () -> Unit, onRun: () -> Unit, onPin: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onFill),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 2.dp, bottom = 2.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                command,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onPin) {
                Icon(
                    if (pinned) XIcons.Star else XIcons.StarBorder,
                    contentDescription = stringResource(if (pinned) R.string.terminal_run_unpin else R.string.terminal_run_pin),
                    tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRun) {
                Icon(XIcons.PlayArrow, contentDescription = stringResource(R.string.terminal_run_go), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
