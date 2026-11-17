package com.invictus.kodex.terminal

class TerminalScheme(val fg: Int, val bg: Int, val cursor: Int, val ansi: IntArray)

object TerminalSchemes {
    private fun c(rgb: Int) = rgb or (0xFF shl 24)
    private fun a(vararg v: Int) = IntArray(16) { c(v[it]) }

    private val darkAnsi = a(
        0x282C34, 0xE06C75, 0x98C379, 0xE5C07B, 0x61AFEF, 0xC678DD, 0x56B6C2, 0xABB2BF,
        0x5C6370, 0xE06C75, 0x98C379, 0xE5C07B, 0x61AFEF, 0xC678DD, 0x56B6C2, 0xFFFFFF,
    )
    private val lightAnsi = a(
        0x2E3436, 0xCC0000, 0x4E9A06, 0xC4A000, 0x3465A4, 0x75507B, 0x06989A, 0xD3D7CF,
        0x555753, 0xEF2929, 0x8AE234, 0xFCE94F, 0x729FCF, 0xAD7FA8, 0x34E2E2, 0xEEEEEC,
    )

    /** id, display name. "kodex" follows the app theme; the rest are fixed palettes. */
    val all = listOf(
        "kodex" to "Kodex",
        "dracula" to "Dracula",
        "nord" to "Nord",
        "tokyo" to "Tokyo Night",
        "onedark" to "One Dark",
        "solarized" to "Solarized Dark",
    )

    fun resolve(id: String, dark: Boolean, themeBg: Int, themeFg: Int, themeAccent: Int): TerminalScheme = when (id) {
        "dracula" -> TerminalScheme(c(0xF8F8F2), c(0x282A36), c(0xF8F8F2), a(
            0x21222C, 0xFF5555, 0x50FA7B, 0xF1FA8C, 0xBD93F9, 0xFF79C6, 0x8BE9FD, 0xF8F8F2,
            0x6272A4, 0xFF6E6E, 0x69FF94, 0xFFFFA5, 0xD6ACFF, 0xFF92DF, 0xA4FFFF, 0xFFFFFF))
        "nord" -> TerminalScheme(c(0xD8DEE9), c(0x2E3440), c(0xD8DEE9), a(
            0x3B4252, 0xBF616A, 0xA3BE8C, 0xEBCB8B, 0x81A1C1, 0xB48EAD, 0x88C0D0, 0xE5E9F0,
            0x4C566A, 0xBF616A, 0xA3BE8C, 0xEBCB8B, 0x81A1C1, 0xB48EAD, 0x8FBCBB, 0xECEFF4))
        "tokyo" -> TerminalScheme(c(0xC0CAF5), c(0x1A1B26), c(0xC0CAF5), a(
            0x15161E, 0xF7768E, 0x9ECE6A, 0xE0AF68, 0x7AA2F7, 0xBB9AF7, 0x7DCFFF, 0xA9B1D6,
            0x414868, 0xF7768E, 0x9ECE6A, 0xE0AF68, 0x7AA2F7, 0xBB9AF7, 0x7DCFFF, 0xC0CAF5))
        "onedark" -> TerminalScheme(c(0xABB2BF), c(0x282C34), c(0x528BFF), darkAnsi)
        "solarized" -> TerminalScheme(c(0x839496), c(0x002B36), c(0x93A1A1), a(
            0x073642, 0xDC322F, 0x859900, 0xB58900, 0x268BD2, 0xD33682, 0x2AA198, 0xEEE8D5,
            0x002B36, 0xCB4B16, 0x586E75, 0x657B83, 0x839496, 0x6C71C4, 0x93A1A1, 0xFDF6E3))
        else -> TerminalScheme(themeFg, themeBg, themeAccent, if (dark) darkAnsi else lightAnsi)
    }
}
