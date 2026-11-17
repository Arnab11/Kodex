package com.invictus.kodex.feature.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.invictus.kodex.R
import com.invictus.kodex.core.external.TermuxLauncher
import com.invictus.kodex.core.external.TermuxRunner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

private const val MAX_SHOWN = 20_000
private const val MAX_HISTORY = 10
private const val HISTORY_SEP = '\u0001'

private fun loadHistory(context: Context): List<String> =
    context.getSharedPreferences("termux_run", Context.MODE_PRIVATE)
        .getString("history", "").orEmpty()
        .split(HISTORY_SEP).filter { it.isNotBlank() }

private fun saveHistory(context: Context, items: List<String>) {
    context.getSharedPreferences("termux_run", Context.MODE_PRIVATE)
        .edit().putString("history", items.joinToString(HISTORY_SEP.toString())).apply()
}

/** Wipes the Run dialog's remembered commands (Settings -> Terminal). */
fun clearRunHistory(context: Context) {
    context.getSharedPreferences("termux_run", Context.MODE_PRIVATE).edit().remove("history").apply()
}

/** Commands that make sense for what is in [dir]: project-specific ones first, then the always-useful ones. */
private fun suggestionsFor(dir: File): List<String> {
    fun has(name: String) = File(dir, name).exists()
    return buildList {
        if (has("gradlew")) addAll(listOf("sh gradlew assembleDebug", "sh gradlew test", "sh gradlew clean"))
        if (has("package.json")) addAll(listOf("npm install", "npm run build", "npm test"))
        if (has("requirements.txt")) add("pip install -r requirements.txt")
        if (has("main.py")) add("python main.py")
        if (has("Cargo.toml")) addAll(listOf("cargo build", "cargo run"))
        if (has("go.mod")) addAll(listOf("go build ./...", "go test ./..."))
        if (has("Makefile")) add("make")
        if (has(".git")) addAll(listOf("git status", "git pull", "git log --oneline -10"))
        addAll(listOf("ls -la", "pwd"))
    }.distinct().take(10)
}

/**
 * Runs one command in Termux, in the project folder, and shows its output here: suggestions for the
 * project, recent commands, elapsed time, exit code, copy / share, and a visible-session button for
 * programs that need the keyboard. Output arrives when the command finishes (no live stream).
 */
@Composable
fun TermuxRunDialog(dir: File, onOpenTerminal: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var command by rememberSaveable { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<TermuxRunner.Result?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var wrap by rememberSaveable { mutableStateOf(true) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var tookMs by remember { mutableLongStateOf(0L) }
    val history = remember { mutableStateListOf<String>().apply { addAll(loadHistory(context)) } }
    val suggestions = remember(dir) { suggestionsFor(dir) }
    val errorColor = MaterialTheme.colorScheme.error
    val outScroll = rememberScrollState()

    fun secs(ms: Long) = String.format(Locale.US, "%.1f", ms / 1000.0)

    LaunchedEffect(running) {
        while (running) {
            elapsedMs = SystemClock.elapsedRealtime() - startedAt
            delay(200)
        }
    }
    // Follow the end of the output when a result arrives.
    LaunchedEffect(outScroll.maxValue) { if (result != null) outScroll.scrollTo(outScroll.maxValue) }

    fun rememberCommand(cmd: String) {
        history.remove(cmd)
        history.add(0, cmd)
        while (history.size > MAX_HISTORY) history.removeAt(history.lastIndex)
        saveHistory(context, history.toList())
    }

    fun run() {
        val cmd = command.trim()
        if (cmd.isEmpty() || running) return
        running = true
        result = null
        startedAt = SystemClock.elapsedRealtime()
        elapsedMs = 0L
        rememberCommand(cmd)
        job = scope.launch {
            val r = TermuxRunner.run(context, dir, cmd)
            tookMs = SystemClock.elapsedRealtime() - startedAt
            result = r
            running = false
        }
    }

    fun runInTermux() {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        rememberCommand(cmd)
        if (TermuxLauncher.runCommandVisible(context, dir, cmd)) onDismiss()
        else Toast.makeText(context, R.string.termux_not_ready, Toast.LENGTH_LONG).show()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp).fillMaxWidth(),
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.termux_run_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.termux_run_folder) + ": " + dir.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text(stringResource(R.string.termux_run_hint)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                ChipRow(stringResource(R.string.termux_run_suggested), suggestions) { command = it }
                if (history.isNotEmpty()) {
                    ChipRow(
                        title = stringResource(R.string.termux_run_recent),
                        items = history.toList(),
                        trailing = {
                            TextButton(onClick = { history.clear(); saveHistory(context, emptyList()) }) {
                                Text(stringResource(R.string.termux_run_clear_history))
                            }
                        },
                    ) { command = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (running) {
                        Button(onClick = { job?.cancel(); running = false }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.termux_stop))
                        }
                    } else {
                        Button(onClick = { run() }, enabled = command.isNotBlank(), modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.termux_run))
                        }
                    }
                    OutlinedButton(onClick = { runInTermux() }, enabled = command.isNotBlank(), modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.termux_run_interactive), maxLines = 1)
                    }
                }
                if (running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.termux_run_running, secs(elapsedMs)),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                result?.let { r ->
                    val failed = r.error != null || (r.exitCode ?: 0) != 0
                    val status = (r.error ?: stringResource(R.string.termux_exit_code, r.exitCode ?: -1)) +
                        " \u00B7 " + stringResource(R.string.termux_run_took, secs(tookMs))
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (failed) errorColor else MaterialTheme.colorScheme.primary,
                    )
                    val cut = r.stdout.length > MAX_SHOWN || r.stderr.length > MAX_SHOWN
                    val out = buildAnnotatedString {
                        append(r.stdout.takeLast(MAX_SHOWN))
                        if (r.stderr.isNotEmpty()) {
                            if (r.stdout.isNotEmpty()) append("\n")
                            withStyle(SpanStyle(color = errorColor)) { append(r.stderr.takeLast(MAX_SHOWN)) }
                        }
                    }
                    Box(
                        Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(outScroll),
                    ) {
                        SelectionContainer {
                            Text(
                                text = if (out.isEmpty()) AnnotatedString(stringResource(R.string.termux_no_output)) else out,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                softWrap = wrap,
                                modifier = if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState()),
                            )
                        }
                    }
                    if (cut) {
                        Text(
                            stringResource(R.string.termux_run_truncated, MAX_SHOWN),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val plain = r.stdout + (if (r.stderr.isNotEmpty()) "\n" + r.stderr else "")
                        TextButton(onClick = {
                            context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("termux-output", plain))
                        }) { Text(stringResource(R.string.termux_copy_output)) }
                        TextButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, plain)
                            runCatching {
                                context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }) { Text(stringResource(R.string.termux_run_share)) }
                        TextButton(onClick = { result = null }) { Text(stringResource(R.string.termux_run_clear)) }
                    }
                } ?: Text(
                    stringResource(R.string.termux_run_note),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.termux_run_wrap), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = wrap, onCheckedChange = { wrap = it })
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { onOpenTerminal(); onDismiss() }) { Text(stringResource(R.string.termux_open_terminal)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close_dialog)) }
                }
            }
        }
    }
}

/** A labelled, horizontally scrolling row of command chips; tapping one puts it in the command box. */
@Composable
private fun ChipRow(
    title: String,
    items: List<String>,
    trailing: @Composable (() -> Unit)? = null,
    onPick: (String) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { item ->
                AssistChip(
                    onClick = { onPick(item) },
                    label = { Text(if (item.length > 30) item.take(29) + "\u2026" else item, maxLines = 1) },
                )
            }
        }
    }
}
