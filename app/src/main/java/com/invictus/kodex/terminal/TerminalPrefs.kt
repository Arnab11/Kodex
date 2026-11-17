package com.invictus.kodex.terminal

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Settings of the in-app terminal. Compose-observable; call [ensure] once before reading. */
object TerminalPrefs {
    private var sp: SharedPreferences? = null

    var useInApp by mutableStateOf(false)
        private set
    /** True once Termux's terminal engine (python) was seen installed by the setup screen. */
    var engineReady by mutableStateOf(false)
        private set
    var scheme by mutableStateOf("kodex")
        private set
    var fontSp by mutableStateOf(14)
        private set
    var scrollback by mutableStateOf(2000)
        private set
    var shell by mutableStateOf("bash")
        private set
    var vibrate by mutableStateOf(true)
        private set
    var maxTabs by mutableStateOf(8)
        private set
    var wakeLock by mutableStateOf(false)
        private set

    @Synchronized
    fun ensure(context: Context) {
        if (sp != null) return
        val p = context.applicationContext.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)
        sp = p
        useInApp = p.getBoolean("use_in_app", false)
        engineReady = p.getBoolean("engine_ready", false)
        scheme = p.getString("scheme", "kodex") ?: "kodex"
        fontSp = p.getInt("font_sp", 14).coerceIn(FONT_MIN, FONT_MAX)
        scrollback = p.getInt("scrollback", 2000)
        shell = p.getString("shell", "bash") ?: "bash"
        vibrate = p.getBoolean("vibrate", true)
        maxTabs = p.getInt("max_tabs", 8)
        wakeLock = p.getBoolean("wake_lock", false)
    }

    /** The in-app terminal is only used while the engine is set up AND the user has it switched on. */
    val inAppActive: Boolean get() = useInApp && engineReady

    /** Called with each Termux probe result. First time the engine turns up, the in-app terminal switches on. */
    fun markEngine(ready: Boolean) {
        if (ready == engineReady) return
        engineReady = ready
        sp?.edit()?.putBoolean("engine_ready", ready)?.apply()
        if (ready) updateUseInApp(true)
    }

    fun updateUseInApp(v: Boolean) { useInApp = v; sp?.edit()?.putBoolean("use_in_app", v)?.apply() }
    fun updateScheme(v: String) { scheme = v; sp?.edit()?.putString("scheme", v)?.apply() }
    fun updateFontSp(v: Int) { fontSp = v.coerceIn(FONT_MIN, FONT_MAX); sp?.edit()?.putInt("font_sp", fontSp)?.apply() }
    fun updateScrollback(v: Int) { scrollback = v; sp?.edit()?.putInt("scrollback", v)?.apply() }
    fun updateShell(v: String) { shell = v; sp?.edit()?.putString("shell", v)?.apply() }
    fun updateVibrate(v: Boolean) { vibrate = v; sp?.edit()?.putBoolean("vibrate", v)?.apply() }
    fun updateMaxTabs(v: Int) { maxTabs = v; sp?.edit()?.putInt("max_tabs", v)?.apply() }
    fun updateWakeLock(v: Boolean) { wakeLock = v; sp?.edit()?.putBoolean("wake_lock", v)?.apply() }

    const val FONT_MIN = 8
    const val FONT_MAX = 28
    val SCROLLBACKS = listOf(1000, 2000, 5000, 10000)
    val SHELLS = listOf("bash", "zsh", "fish")
    val TAB_LIMITS = listOf(4, 8, 12)
}
