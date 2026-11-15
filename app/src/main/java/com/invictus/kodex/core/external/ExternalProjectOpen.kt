package com.invictus.kodex.core.external

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * "Open this folder as the project" requests from outside the UI (the `kodex <folder>` command in
 * Termux). MainActivity posts the folder, the nav host brings the workspace forward and the
 * workspace consumes it, so the request survives until a screen can act on it.
 */
object ExternalProjectOpen {
    /** Intent action sent by the `kodex` Termux command for a folder. */
    const val ACTION = "com.invictus.kodex.OPEN_DIR"
    const val EXTRA_PATH = "path"

    private val _pending = MutableStateFlow<File?>(null)
    val pending: StateFlow<File?> = _pending

    fun request(dir: File) {
        _pending.value = dir
    }

    /** Clears [dir] only if it is still the pending request (a newer one stays). */
    fun consume(dir: File) {
        if (_pending.value == dir) _pending.value = null
    }
}
