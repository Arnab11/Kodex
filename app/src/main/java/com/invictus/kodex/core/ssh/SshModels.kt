package com.invictus.kodex.core.ssh

import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

enum class SshAuthType {
    PASSWORD,
    PRIVATE_KEY,
}

data class SshHostConfig(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val host: String = "",
    val port: Int = DEFAULT_PORT,
    val username: String = "",
    val authType: SshAuthType = SshAuthType.PASSWORD,
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    val defaultRemotePath: String = "",
    val rememberCredentials: Boolean = true,
    val lastConnectedAt: Long = 0L,
) {
    val displayName: String
        get() = label.trim().ifEmpty { endpoint }

    val endpoint: String
        get() = buildString {
            if (username.isNotBlank()) {
                append(username.trim())
                append('@')
            }
            append(host.trim())
            if (port != DEFAULT_PORT && port > 0) {
                append(':')
                append(port)
            }
        }

    companion object {
        const val DEFAULT_PORT = 22
    }
}

data class SftpEntry(
    val name: String,
    val remotePath: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val modifiedMs: Long = 0L,
)

data class SshHostKeyPrompt(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
    val isMismatch: Boolean,
    val response: CompletableDeferred<Boolean>,
)

data class SshCredentialPrompt(
    val host: SshHostConfig,
    val promptLabel: String,
    val isPassphrase: Boolean,
    val response: CompletableDeferred<String?>,
)

sealed interface SshConnectionState {
    data object Disconnected : SshConnectionState
    data class Connecting(val host: SshHostConfig, val step: String) : SshConnectionState
    data class Connected(
        val host: SshHostConfig,
        val remoteRoot: String,
        val mirrorRoot: File,
    ) : SshConnectionState
    data class Error(val host: SshHostConfig, val message: String) : SshConnectionState
}

interface SshShellHandle {
    val inputStream: InputStream
    val outputStream: OutputStream
    fun resize(cols: Int, rows: Int)
    fun isConnected(): Boolean
    fun exitStatus(): Int
    fun close()
}

/**
 * Parses VS Code / Antigravity style quick-connect strings:
 * - `user@host`
 * - `user@host:2222`
 * - `ssh user@host`
 * - `ssh user@host -p 2222`
 * - `ssh -p 2222 user@host`
 * - `host` (leaves username blank or uses [defaultUser])
 */
object SshQuickConnectParser {
    fun parse(raw: String, defaultUser: String = "root"): SshHostConfig? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val tokens = trimmed.split(Regex("\\s+")).toMutableList()
        if (tokens.firstOrNull()?.equals("ssh", ignoreCase = true) == true) {
            tokens.removeAt(0)
        }
        if (tokens.isEmpty()) return null

        var port = SshHostConfig.DEFAULT_PORT
        var target: String? = null
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            when {
                tok == "-p" && i + 1 < tokens.size -> {
                    port = tokens[i + 1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                    i += 2
                }
                tok.startsWith("-p") && tok.length > 2 -> {
                    port = tok.substring(2).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                    i++
                }
                tok.startsWith("-") -> {
                    // Skip unknown single flag; if it's -i <path> or similar, skip its arg if not last
                    if (tok in setOf("-i", "-l", "-J", "-F", "-o") && i + 1 < tokens.size) {
                        if (tok == "-l") target = "${tokens[i + 1]}@${target ?: ""}"
                        i += 2
                    } else {
                        i++
                    }
                }
                else -> {
                    target = tok
                    i++
                }
            }
        }

        val cleanTarget = target?.trim().orEmpty()
        if (cleanTarget.isEmpty()) return null

        val atIdx = cleanTarget.lastIndexOf('@')
        val user: String
        val hostPort: String
        if (atIdx >= 0) {
            user = cleanTarget.substring(0, atIdx).trim()
            hostPort = cleanTarget.substring(atIdx + 1).trim()
        } else {
            user = defaultUser
            hostPort = cleanTarget
        }
        if (hostPort.isEmpty()) return null

        val host: String
        // Handle IPv6 `[::1]:2222` or `host:2222`
        if (hostPort.startsWith("[")) {
            val closeBracket = hostPort.indexOf(']')
            if (closeBracket < 0) return null
            host = hostPort.substring(1, closeBracket)
            val rest = hostPort.substring(closeBracket + 1)
            if (rest.startsWith(":")) {
                port = rest.substring(1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            }
        } else {
            val colonIdx = hostPort.lastIndexOf(':')
            if (colonIdx > 0 && hostPort.indexOf(':') == colonIdx) {
                host = hostPort.substring(0, colonIdx)
                port = hostPort.substring(colonIdx + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            } else {
                host = hostPort
            }
        }

        if (host.isBlank() || user.isBlank()) return null

        return SshHostConfig(
            host = host,
            port = port,
            username = user,
        )
    }
}
