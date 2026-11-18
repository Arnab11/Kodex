package com.invictus.kodex.feature.terminal

import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.invictus.kodex.R
import com.invictus.kodex.core.external.TermuxLauncher
import com.invictus.kodex.terminal.RemoteSession
import com.invictus.kodex.terminal.TerminalHub
import com.invictus.kodex.terminal.TerminalPrefs
import com.invictus.kodex.terminal.TerminalSchemes
import com.invictus.kodex.ui.icons.XIcons
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.launch

/** Sticky Ctrl / Alt from the extra-keys row: tap = next key only, long press = lock. */
private class Mods {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    var ctrlLock by mutableStateOf(false)
    var altLock by mutableStateOf(false)

    fun takeCtrl(): Boolean { val v = ctrl; if (!ctrlLock) ctrl = false; return v }
    fun takeAlt(): Boolean { val v = alt; if (!altLock) alt = false; return v }
}

private class Client(private val mods: Mods, private val onZoom: (Int) -> Unit) : TerminalViewClient {
    var view: TerminalView? = null

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            onZoom(if (scale > 1f) 1 else -1)
            return 1f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) {
        val v = view ?: return
        v.requestFocus()
        v.context.getSystemService(InputMethodManager::class.java)?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: RemoteSession?) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
    override fun onLongPress(event: MotionEvent) = false
    override fun readControlKey() = mods.takeCtrl()
    override fun readAltKey() = mods.takeAlt()
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: RemoteSession?) = false
    override fun onEmulatorSet() {}
    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}
}

private val ACCENTS = listOf(
    Color(0xFF61AFEF), Color(0xFF98C379), Color(0xFFE5C07B), Color(0xFFC678DD),
    Color(0xFF56B6C2), Color(0xFFE06C75), Color(0xFFD19A66), Color(0xFFABB2BF),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    onBack: () -> Unit,
    onOpenSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext
    TerminalPrefs.ensure(app)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val tabs = TerminalHub.tabs
    val selected = tabs.firstOrNull { it.id == TerminalHub.selectedId } ?: tabs.firstOrNull()

    LaunchedEffect(Unit) {
        if (TerminalHub.tabs.isEmpty()) TerminalHub.newTab(app, TerminalHub.lastDir ?: app.filesDir)
    }

    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    val scheme = remember(TerminalPrefs.scheme, dark, cs.surfaceContainerLowest, cs.onSurface, cs.primary) {
        TerminalSchemes.resolve(TerminalPrefs.scheme, dark, cs.surfaceContainerLowest.toArgb(), cs.onSurface.toArgb(), cs.primary.toArgb())
    }
    LaunchedEffect(scheme, tabs.size) { tabs.forEach { it.session.applyScheme(scheme) } }
    DisposableEffect(Unit) {
        onDispose { TerminalHub.tabs.forEach { it.session.onScreen = null; it.session.onBellRing = null } }
    }

    val mods = remember { Mods() }
    val client = remember { Client(mods) { dir -> TerminalPrefs.updateFontSp(TerminalPrefs.fontSp + dir) } }
    var termView by remember { mutableStateOf<TerminalView?>(null) }
    val fontPx = with(LocalDensity.current) { TerminalPrefs.fontSp.sp.toPx().toInt() }

    var menuOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var runOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<TerminalHub.Tab?>(null) }

    fun newTab() {
        if (TerminalHub.newTab(app, selected?.session?.dir ?: TerminalHub.lastDir ?: app.filesDir) == null) {
            scope.launch { snackbar.showSnackbar(app.getString(R.string.terminal_tab_limit, TerminalPrefs.maxTabs)) }
        }
    }
    // The focused TerminalView keeps the IME up for a moment after the screen is popped: drop it first.
    fun hideKeyboard() {
        termView?.let { v ->
            v.clearFocus()
            v.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0)
        }
    }
    fun leave() {
        hideKeyboard()
        onBack()
    }
    fun closeTab(tab: TerminalHub.Tab) {
        TerminalHub.close(tab)
        if (TerminalHub.tabs.isEmpty()) leave()
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) { Snackbar(it) } },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.terminal_title)) },
                navigationIcon = { IconButton(onClick = ::leave) { Icon(XIcons.ArrowBack, null) } },
                actions = {
                    IconButton(onClick = { runOpen = true }, enabled = selected != null) {
                        Icon(XIcons.PlayArrow, stringResource(R.string.terminal_set_run_group))
                    }
                    IconButton(onClick = ::newTab) { Icon(XIcons.Add, stringResource(R.string.terminal_new_tab)) }
                    IconButton(onClick = { menuOpen = true }) { Icon(XIcons.MoreVert, null) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.terminal_paste)) },
                            leadingIcon = { Icon(XIcons.ContentPaste, null) },
                            onClick = { menuOpen = false; selected?.session?.pasteClipboard() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.terminal_copy_all)) },
                            leadingIcon = { Icon(XIcons.ContentCopy, null) },
                            onClick = { menuOpen = false; selected?.session?.copyTranscript() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.terminal_clear)) },
                            leadingIcon = { Icon(XIcons.DeleteSweep, null) },
                            onClick = { menuOpen = false; selected?.session?.clearAll() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.terminal_menu_termux)) },
                            leadingIcon = { Icon(XIcons.OpenInNew, null) },
                            onClick = {
                                menuOpen = false
                                TermuxLauncher.openSession(context, selected?.session?.dir ?: app.filesDir)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.terminal_settings)) },
                            leadingIcon = { Icon(XIcons.Settings, null) },
                            onClick = { menuOpen = false; settingsOpen = true },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabStrip(
                tabs = tabs,
                selectedId = selected?.id ?: -1,
                onSelect = { TerminalHub.selectedId = it.id },
                onClose = ::closeTab,
                onRename = { renameTarget = it },
                onDuplicate = { TerminalHub.duplicate(app, it) ?: scope.launch { snackbar.showSnackbar(app.getString(R.string.terminal_tab_limit, TerminalPrefs.maxTabs)) } },
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(scheme.bg)),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                    factory = { ctx ->
                        TerminalView(ctx, null).also { v ->
                            client.view = v
                            v.setTerminalViewClient(client)
                            // The renderer only exists after setTextSize; setTypeface before it is an NPE.
                            v.setTextSize(fontPx)
                            v.tag = fontPx
                            v.setTypeface(Typeface.MONOSPACE)
                            v.isFocusable = true
                            v.isFocusableInTouchMode = true
                            termView = v
                        }
                    },
                    update = { v ->
                        if (v.tag != fontPx) { v.tag = fontPx; v.setTextSize(fontPx) }
                        val session = selected?.session
                        if (session != null && v.currentSession !== session) {
                            session.onScreen = { v.onScreenUpdated() }
                            session.onBellRing = {
                                if (TerminalPrefs.vibrate) v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            }
                            session.applyScheme(scheme)
                            v.attachSession(session)
                            v.requestFocus()
                            v.onScreenUpdated()
                        }
                    },
                )
                if (selected != null) StatusOverlay(
                    tab = selected,
                    bg = Color(scheme.bg),
                    fg = Color(scheme.fg),
                    onRetry = {
                        // Close first (frees the tab slot) but never leave the screen: the new tab opens right away.
                        val dir = selected.session.dir
                        TerminalHub.close(selected)
                        TerminalHub.newTab(app, dir)
                    },
                    onClose = { closeTab(selected) },
                    onSetup = onOpenSetup,
                    onOpenTermux = { TermuxLauncher.openSession(context, selected.session.dir) },
                )
            }
            ExtraKeys(mods = mods, view = { termView }, session = { selected?.session })
        }
    }

    renameTarget?.let { tab ->
        var text by remember(tab) { mutableStateOf(tab.customName ?: "") }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.terminal_rename)) },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { tab.customName = text.trim().ifEmpty { null }; renameTarget = null }) {
                    Text(stringResource(R.string.terminal_ok))
                }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.terminal_cancel)) } },
        )
    }

    if (runOpen && selected != null) {
        TerminalRunSheet(
            dir = selected.session.dir,
            canRunHere = selected.session.state == RemoteSession.State.RUNNING,
            onRun = { cmd, inNewTab ->
                if (!inNewTab) {
                    selected.session.write(cmd + "\n")
                } else if (TerminalHub.newTab(app, selected.session.dir, cmd + "\n") == null) {
                    scope.launch { snackbar.showSnackbar(app.getString(R.string.terminal_tab_limit, TerminalPrefs.maxTabs)) }
                }
            },
            onDismiss = { runOpen = false },
        )
    }

    if (settingsOpen) {
        ModalBottomSheet(onDismissRequest = { settingsOpen = false }) {
            Column(Modifier.verticalScroll(rememberScrollState())) { TerminalSettingsContent() }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabStrip(
    tabs: List<TerminalHub.Tab>,
    selectedId: Int,
    onSelect: (TerminalHub.Tab) -> Unit,
    onClose: (TerminalHub.Tab) -> Unit,
    onRename: (TerminalHub.Tab) -> Unit,
    onDuplicate: (TerminalHub.Tab) -> Unit,
) {
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(tabs, key = { it.id }) { tab ->
            var menu by remember { mutableStateOf(false) }
            val isSel = tab.id == selectedId
            val accent = ACCENTS[tab.accent % ACCENTS.size]
            val label = tab.customName ?: tab.session.title.ifBlank { tab.session.dir.name.ifBlank { "~" } }
            Box {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSel) accent.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .combinedClickable(onClick = { onSelect(tab) }, onLongClick = { menu = true }),
                ) {
                    Row(Modifier.padding(start = 10.dp, end = 2.dp).height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(8.dp).clip(CircleShape).background(
                                when (tab.session.state) {
                                    RemoteSession.State.RUNNING -> Color(0xFF4CAF50)
                                    RemoteSession.State.STARTING, RemoteSession.State.RECONNECTING -> Color(0xFFFFB300)
                                    else -> Color(0xFFE53935)
                                },
                            ),
                        )
                        Text(
                            label,
                            Modifier.padding(horizontal = 8.dp).widthIn(max = 140.dp),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = { onClose(tab) }, Modifier.size(28.dp)) { Icon(XIcons.Close, null, Modifier.size(16.dp)) }
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.terminal_rename)) }, onClick = { menu = false; onRename(tab) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.terminal_duplicate)) }, onClick = { menu = false; onDuplicate(tab) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.terminal_close_tab)) }, onClick = { menu = false; onClose(tab) })
                }
            }
        }
    }
}

@Composable
private fun StatusOverlay(
    tab: TerminalHub.Tab,
    bg: Color,
    fg: Color,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onSetup: () -> Unit,
    onOpenTermux: () -> Unit,
) {
    val session = tab.session
    when (session.state) {
        RemoteSession.State.STARTING -> Box(Modifier.fillMaxSize().background(bg), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(28.dp), color = fg)
                Text(stringResource(R.string.terminal_starting), color = fg, style = MaterialTheme.typography.bodyMedium)
            }
        }
        RemoteSession.State.FAILED -> Box(Modifier.fillMaxSize().background(bg).padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(session.message.orEmpty(), color = fg, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onRetry) { Text(stringResource(R.string.terminal_retry)) }
                OutlinedButton(onClick = onSetup) { Text(stringResource(R.string.termux_setup_action)) }
                TextButton(onClick = onOpenTermux) { Text(stringResource(R.string.terminal_open_termux)) }
            }
        }
        RemoteSession.State.EXITED -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                Modifier.padding(12.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.terminal_exited), Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.terminal_restart)) }
                    TextButton(onClick = onClose) { Text(stringResource(R.string.terminal_close_tab)) }
                }
            }
        }
        RemoteSession.State.RECONNECTING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Surface(
                Modifier.padding(12.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.terminal_reconnecting), Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        RemoteSession.State.RUNNING -> Unit
    }
}

private class KeyDef(val label: String, val keyCode: Int = 0, val text: String? = null, val special: Char = ' ')

private val KEYS = listOf(
    KeyDef("ESC", KeyEvent.KEYCODE_ESCAPE), KeyDef("TAB", KeyEvent.KEYCODE_TAB),
    KeyDef("CTRL", special = 'c'), KeyDef("ALT", special = 'a'),
    KeyDef("\u2190", KeyEvent.KEYCODE_DPAD_LEFT), KeyDef("\u2191", KeyEvent.KEYCODE_DPAD_UP),
    KeyDef("\u2193", KeyEvent.KEYCODE_DPAD_DOWN), KeyDef("\u2192", KeyEvent.KEYCODE_DPAD_RIGHT),
    KeyDef("/", text = "/"), KeyDef("-", text = "-"), KeyDef("|", text = "|"), KeyDef("~", text = "~"),
    KeyDef(":", text = ":"), KeyDef("&", text = "&"), KeyDef("*", text = "*"), KeyDef("_", text = "_"),
    KeyDef("\"", text = "\""), KeyDef("'", text = "'"),
    KeyDef("HOME", KeyEvent.KEYCODE_MOVE_HOME), KeyDef("END", KeyEvent.KEYCODE_MOVE_END),
    KeyDef("PGUP", KeyEvent.KEYCODE_PAGE_UP), KeyDef("PGDN", KeyEvent.KEYCODE_PAGE_DOWN),
    KeyDef("^C", text = "\u0003"), KeyDef("^D", text = "\u0004"),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExtraKeys(mods: Mods, view: () -> TerminalView?, session: () -> RemoteSession?) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        KEYS.forEach { k ->
            val active = when (k.special) { 'c' -> mods.ctrl; 'a' -> mods.alt; else -> false }
            val locked = when (k.special) { 'c' -> mods.ctrlLock; 'a' -> mods.altLock; else -> false }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = when {
                    locked -> MaterialTheme.colorScheme.primary
                    active -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .combinedClickable(
                        onClick = {
                            when {
                                k.special == 'c' -> { mods.ctrl = !mods.ctrl; mods.ctrlLock = false }
                                k.special == 'a' -> { mods.alt = !mods.alt; mods.altLock = false }
                                k.keyCode != 0 -> view()?.let { v ->
                                    v.onKeyDown(k.keyCode, KeyEvent(KeyEvent.ACTION_DOWN, k.keyCode))
                                    v.onKeyUp(k.keyCode, KeyEvent(KeyEvent.ACTION_UP, k.keyCode))
                                }
                                k.text != null -> session()?.write(k.text)
                            }
                        },
                        onLongClick = {
                            when (k.special) {
                                'c' -> { mods.ctrlLock = !mods.ctrlLock; mods.ctrl = mods.ctrlLock }
                                'a' -> { mods.altLock = !mods.altLock; mods.alt = mods.altLock }
                            }
                        },
                    ),
            ) {
                Box(Modifier.widthIn(min = 44.dp).height(40.dp).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                    Text(
                        k.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace,
                        color = if (locked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
