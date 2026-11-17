package com.invictus.kodex.feature.terminal

import android.content.Context
import java.io.File

/** Remembered commands of the Run sheet: recent ones and pinned favourites (same prefs file the old Run dialog used). */
object RunStore {
    private const val MAX_HISTORY = 30
    private const val SEP = '\u0001'

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("termux_run", Context.MODE_PRIVATE)

    private fun read(context: Context, key: String): List<String> =
        prefs(context).getString(key, "").orEmpty().split(SEP).filter { it.isNotBlank() }

    private fun write(context: Context, key: String, items: List<String>) {
        prefs(context).edit().putString(key, items.joinToString(SEP.toString())).apply()
    }

    fun history(context: Context): List<String> = read(context, "history")
    fun pins(context: Context): List<String> = read(context, "pins")

    /** Puts [command] first in the recent list (no duplicates). */
    fun record(context: Context, command: String) {
        write(context, "history", (listOf(command) + history(context).filter { it != command }).take(MAX_HISTORY))
    }

    fun setPinned(context: Context, command: String, pinned: Boolean) {
        val rest = pins(context).filter { it != command }
        write(context, "pins", if (pinned) listOf(command) + rest else rest)
    }

    /** Forgets the recent commands; pinned ones stay. */
    fun clearHistory(context: Context) {
        prefs(context).edit().remove("history").apply()
    }

    /** Commands that make sense for what is in [dir]: project-specific ones first, then the always-useful ones. */
    fun suggestionsFor(dir: File): List<String> {
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
}
