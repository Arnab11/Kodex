package com.invictus.kodex.terminal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.invictus.kodex.R
import com.invictus.kodex.core.external.TermuxLauncher
import com.invictus.kodex.core.external.TermuxRunFile
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Process-wide owner of the terminal tabs, so leaving the screen (or Kodex going to the background)
 * does not kill the shells. Tabs live until closed or until the process dies.
 */
object TerminalHub {
    class Tab(val id: Int, val session: RemoteSession, val accent: Int) {
        var customName by mutableStateOf<String?>(null)
    }

    val tabs = mutableStateListOf<Tab>()
    var selectedId by mutableIntStateOf(-1)
    var lastDir: File? = null

    /** The nav host listens and shows the terminal screen. */
    val navEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private var nextId = 1

    /** Workspace "Terminal": reuse a live tab for [dir] if there is one, else open a new tab. */
    fun open(context: Context, dir: File): Boolean {
        TerminalPrefs.ensure(context)
        lastDir = dir
        val live = tabs.firstOrNull {
            it.session.dir == dir && (it.session.state == RemoteSession.State.RUNNING || it.session.state == RemoteSession.State.STARTING)
        }
        if (live != null) selectedId = live.id else newTab(context, dir)
        navEvents.tryEmit(Unit)
        return true
    }

    /** Editor "Run file": always a fresh tab in the file's folder; the command is typed into the shell. */
    fun runFile(context: Context, file: File): Boolean {
        TerminalPrefs.ensure(context)
        val command = TermuxRunFile.inlineCommand(file) ?: return false
        val dir = file.parentFile ?: return false
        lastDir = dir
        newTab(context, dir, command + "\n")
        navEvents.tryEmit(Unit)
        return true
    }

    fun newTab(context: Context, dir: File, input: String? = null): Tab? {
        val app = context.applicationContext
        TerminalPrefs.ensure(app)
        if (tabs.size >= TerminalPrefs.maxTabs) return null
        val id = nextId++
        val session = RemoteSession(app, id, dir, TerminalPrefs.scrollback)
        val tab = Tab(id, session, (id - 1) % ACCENT_COUNT)
        tabs.add(tab)
        selectedId = id
        launch(app, session, input)
        return tab
    }

    fun duplicate(context: Context, tab: Tab): Tab? = newTab(context, tab.session.dir)

    fun close(tab: Tab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        tabs.removeAt(index)
        tab.session.close()
        if (selectedId == tab.id) selectedId = tabs.getOrNull(index.coerceAtMost(tabs.size - 1))?.id ?: -1
    }

    private fun launch(app: Context, session: RemoteSession, input: String?) {
        val port = try {
            ServerSocket(0).use { it.localPort }
        } catch (_: IOException) {
            session.fail(app.getString(R.string.terminal_err_refused))
            return
        }
        val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val command = PtydHelper.launchCommand(port, token, session.dir, TerminalPrefs.shell, 80, 24, TerminalPrefs.wakeLock)
        if (!TermuxLauncher.startBackground(app, arrayOf("-lc", command), null, null)) {
            session.fail(app.getString(R.string.terminal_err_refused))
            return
        }
        session.connect(port, token, input)
    }

    const val ACCENT_COUNT = 8
}
