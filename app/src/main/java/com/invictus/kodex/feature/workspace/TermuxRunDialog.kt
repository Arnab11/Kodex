package com.invictus.kodex.feature.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.invictus.kodex.R
import com.invictus.kodex.core.external.TermuxRunner
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

private const val MAX_SHOWN = 20_000
private const val MAX_HISTORY = 6
private const val HISTORY_SEP = '\u0001'

private fun loadHistory(context: Context): List<String> =
    context.getSharedPreferences("termux_run", Context.MODE_PRIVATE)
        .getString("history", "").orEmpty()
        .split(HISTORY_SEP).filter { it.isNotBlank() }

private fun saveHistory(context: Context, items: List<String>) {
    context.getSharedPreferences("termux_run", Context.MODE_PRIVATE)
        .edit().putString("history", items.joinToString(HISTORY_SEP.toString())).apply()
}

/**
 * Runs one command in Termux, in the project folder, and shows its output here. Output arrives when
 * the command finishes (no live stream, no stdin); the Termux button is for interactive work.
 */
@Composable
fun TermuxRunDialog(dir: File, onOpenTerminal: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var command by rememberSaveable { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<TermuxRunner.Result?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val history = remember { mutableStateListOf<String>().apply { addAll(loadHistory(context)) } }
    val errorColor = MaterialTheme.colorScheme.error

    fun run() {
        val cmd = command.trim()
        if (cmd.isEmpty() || running) return
        running = true
        result = null
        history.remove(cmd)
        history.add(0, cmd)
        while (history.size > MAX_HISTORY) history.removeAt(history.lastIndex)
        saveHistory(context, history.toList())
        job = scope.launch {
            result = TermuxRunner.run(context, dir, cmd)
            running = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.termux_run_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text(stringResource(R.string.termux_run_hint)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (history.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        history.forEach { h ->
                            AssistChip(
                                onClick = { command = h },
                                label = { Text(if (h.length > 28) h.take(27) + "…" else h, maxLines = 1) },
                            )
                        }
                    }
                }
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
                result?.let { r ->
                    val status = r.error ?: stringResource(R.string.termux_exit_code, r.exitCode ?: -1)
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (r.error != null || (r.exitCode ?: 0) != 0) errorColor else MaterialTheme.colorScheme.primary,
                    )
                    val out = buildAnnotatedString {
                        append(r.stdout.takeLast(MAX_SHOWN))
                        if (r.stderr.isNotEmpty()) {
                            if (r.stdout.isNotEmpty()) append("\n")
                            withStyle(SpanStyle(color = errorColor)) { append(r.stderr.takeLast(MAX_SHOWN)) }
                        }
                    }
                    Box(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                        SelectionContainer {
                            Text(
                                text = if (out.isEmpty()) androidx.compose.ui.text.AnnotatedString(stringResource(R.string.termux_no_output)) else out,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    Row {
                        TextButton(
                            onClick = {
                                val text = r.stdout + (if (r.stderr.isNotEmpty()) "\n" + r.stderr else "")
                                context.getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(ClipData.newPlainText("termux-output", text))
                            },
                        ) { Text(stringResource(R.string.termux_copy_output)) }
                    }
                } ?: Text(
                    stringResource(R.string.termux_run_note),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            if (running) {
                TextButton(onClick = { job?.cancel(); running = false }) { Text(stringResource(R.string.termux_stop)) }
            } else {
                TextButton(onClick = { run() }, enabled = command.isNotBlank()) { Text(stringResource(R.string.termux_run)) }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onOpenTerminal(); onDismiss() }) { Text(stringResource(R.string.termux_open_terminal)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close_dialog)) }
            }
        },
    )
}
