package com.invictus.kodex.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.invictus.kodex.R
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.concurrent.thread

/**
 * One terminal tab. The shell runs inside Termux (python helper `kodex-ptyd`, 127.0.0.1 only);
 * this class is the other end of the socket and feeds a Termux [TerminalEmulator], so the vendored
 * `TerminalView` can draw it. It replaces Termux's `TerminalSession`, which insists on a local PTY.
 *
 * Wire format (both directions): 1 byte type, 4 bytes big-endian length, payload.
 */
class RemoteSession(
    private val appContext: Context,
    val id: Int,
    val dir: File,
    scrollback: Int,
) : TerminalOutput(), TerminalSessionClient {

    enum class State { STARTING, RUNNING, EXITED, FAILED }

    var state by mutableStateOf(State.STARTING)
        private set
    var title by mutableStateOf("")
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var exitCode by mutableStateOf<Int?>(null)
        private set

    /** Set by the view host: redraw after new output or a colour change. */
    var onScreen: (() -> Unit)? = null
    var onBellRing: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "kodex-term-w$id").apply { isDaemon = true } }
    private val lock = Any()

    @Volatile private var out: OutputStream? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var closed = false
    @Volatile private var cols = 80
    @Volatile private var rows = 24
    private var gotData = false

    /** Declared last: the emulator calls back into this object while it is being built. */
    val emulator: TerminalEmulator = TerminalEmulator(this, 80, 24, scrollback, this)

    // ---------------------------------------------------------------- connection

    fun connect(port: Int, token: String, firstInput: String?) {
        thread(name = "kodex-term-r$id", isDaemon = true) {
            val s = dial(port)
            if (s == null) {
                main.post { if (!closed) fail(appContext.getString(R.string.terminal_err_no_helper)) }
                return@thread
            }
            try {
                socket = s
                synchronized(lock) { out = s.getOutputStream() }
                writeFrame(T_HELLO, token.toByteArray())
                sendResize()
                if (!firstInput.isNullOrEmpty()) write(firstInput)
                val input = DataInputStream(BufferedInputStream(s.getInputStream()))
                while (true) {
                    val type = input.read()
                    if (type < 0) break
                    val len = input.readInt()
                    if (len < 0 || len > MAX_FRAME) break
                    val payload = ByteArray(len)
                    input.readFully(payload)
                    when (type) {
                        T_DATA -> main.post { onData(payload) }
                        T_EXIT -> {
                            val code = if (len >= 4) ByteBuffer.wrap(payload).int else -1
                            main.post { onExit(code) }
                            return@thread
                        }
                    }
                }
                main.post { onLost() }
            } catch (_: IOException) {
                main.post { onLost() }
            }
        }
    }

    private fun dial(port: Int): Socket? {
        val deadline = SystemClock.elapsedRealtime() + CONNECT_TIMEOUT_MS
        while (!closed && SystemClock.elapsedRealtime() < deadline) {
            val s = Socket()
            try {
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 400)
                return s
            } catch (_: IOException) {
                try { s.close() } catch (_: IOException) {}
                try { Thread.sleep(300) } catch (_: InterruptedException) { return null }
            }
        }
        return null
    }

    private fun writeFrame(type: Int, data: ByteArray) {
        synchronized(lock) {
            val o = out ?: return
            val header = ByteBuffer.allocate(5).put(type.toByte()).putInt(data.size).array()
            o.write(header)
            if (data.isNotEmpty()) o.write(data)
            o.flush()
        }
    }

    /** All traffic from the UI thread goes through one worker: socket writes must not run on main. */
    private fun queue(type: Int, data: ByteArray) {
        if (closed) return
        try {
            io.execute {
                try { writeFrame(type, data) } catch (_: IOException) {}
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun sendResize() {
        queue(T_RESIZE, ByteBuffer.allocate(4).putShort(cols.toShort()).putShort(rows.toShort()).array())
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            io.execute {
                try { writeFrame(T_CLOSE, ByteArray(0)) } catch (_: IOException) {}
                closeSocket()
            }
        } catch (_: RejectedExecutionException) {
        }
        io.shutdown()
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: IOException) {}
    }

    // ---------------------------------------------------------------- state (main thread)

    fun fail(text: String) {
        state = State.FAILED
        message = text
    }

    private fun onData(payload: ByteArray) {
        if (closed) return
        if (!gotData) {
            gotData = true
            state = State.RUNNING
        }
        emulator.append(payload, payload.size)
        onScreen?.invoke()
    }

    private fun onExit(code: Int) {
        if (closed || state == State.EXITED) return
        state = State.EXITED
        exitCode = code
        banner("[Process completed - exit $code]")
        closeSocket()
    }

    private fun onLost() {
        if (closed || state == State.EXITED || state == State.FAILED) return
        state = State.EXITED
        banner("[Connection to Termux lost]")
        closeSocket()
    }

    private fun banner(text: String) {
        val bytes = "\r\n\u001b[2m$text\u001b[0m\r\n".toByteArray()
        emulator.append(bytes, bytes.size)
        onScreen?.invoke()
    }

    // ---------------------------------------------------------------- called by TerminalView

    fun updateSize(columns: Int, rows: Int) {
        cols = columns
        this.rows = rows
        emulator.resize(columns, rows)
        sendResize()
    }

    fun writeCodePoint(prependEscape: Boolean, codePoint: Int) {
        if (codePoint > 0x10FFFF || codePoint in 0xD800..0xDFFF) return
        val sb = StringBuilder()
        if (prependEscape) sb.append('\u001b')
        sb.appendCodePoint(codePoint)
        write(sb.toString())
    }

    override fun write(data: ByteArray, offset: Int, count: Int) {
        if (state == State.STARTING || state == State.RUNNING) queue(T_DATA, data.copyOfRange(offset, offset + count))
    }

    fun reset() {
        emulator.reset()
        onScreen?.invoke()
    }

    // ---------------------------------------------------------------- toolbar helpers

    fun pasteClipboard() {
        val cm = appContext.getSystemService(ClipboardManager::class.java) ?: return
        val clip = cm.primaryClip?.takeIf { it.itemCount > 0 } ?: return
        val text = clip.getItemAt(0).coerceToText(appContext)?.toString()
        if (!text.isNullOrEmpty()) emulator.paste(text)
    }

    fun copyTranscript() {
        val text = emulator.screen.transcriptText?.trimEnd().orEmpty()
        if (text.isNotEmpty()) onCopyTextToClipboard(text)
    }

    fun applyScheme(s: TerminalScheme) {
        val c = emulator.mColors.mCurrentColors
        for (i in 0 until 16) c[i] = s.ansi[i]
        c[256] = s.fg // TextStyle.COLOR_INDEX_FOREGROUND
        c[257] = s.bg // COLOR_INDEX_BACKGROUND
        c[258] = s.cursor // COLOR_INDEX_CURSOR
        onScreen?.invoke()
    }

    // ---------------------------------------------------------------- TerminalOutput

    override fun titleChanged(oldTitle: String?, newTitle: String?) {
        title = newTitle.orEmpty()
    }

    override fun onCopyTextToClipboard(text: String?) {
        if (text.isNullOrEmpty()) return
        appContext.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard() = pasteClipboard()

    override fun onBell() {
        onBellRing?.invoke()
    }

    override fun onColorsChanged() {
        onScreen?.invoke()
    }

    // ---------------------------------------------------------------- TerminalSessionClient (the emulator only logs / asks the cursor style)

    override fun onTextChanged(changedSession: TerminalSession?) {}
    override fun onTitleChanged(changedSession: TerminalSession?) {}
    override fun onSessionFinished(finishedSession: TerminalSession?) {}
    override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) = onCopyTextToClipboard(text)
    override fun onPasteTextFromClipboard(session: TerminalSession?) = onPasteTextFromClipboard()
    override fun onBell(session: TerminalSession?) {}
    override fun onColorsChanged(session: TerminalSession?) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}

    companion object {
        const val T_HELLO = 0
        const val T_DATA = 1
        const val T_RESIZE = 2
        const val T_CLOSE = 3
        const val T_EXIT = 4
        private const val MAX_FRAME = 1 shl 20
        private const val CONNECT_TIMEOUT_MS = 15_000L
    }
}
