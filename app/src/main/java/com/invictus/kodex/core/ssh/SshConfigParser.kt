package com.invictus.kodex.core.ssh

import android.os.Environment
import java.io.File

/**
 * Parses OpenSSH `config` files (e.g. `/storage/emulated/0/.ssh/config`) into [SshHostConfig]
 * entries, matching VS Code Remote-SSH's auto-detection of `Host`, `HostName`, `User`, `Port`,
 * and `IdentityFile`.
 */
object SshConfigParser {

    fun defaultConfigFiles(appFilesDir: File): List<File> = listOf(
        File(Environment.getExternalStorageDirectory(), ".ssh/config"),
        File(appFilesDir, ".ssh/config"),
    )

    fun loadFromDefaultLocations(appFilesDir: File): List<SshHostConfig> {
        val seenIds = HashSet<String>()
        val result = ArrayList<SshHostConfig>()
        for (file in defaultConfigFiles(appFilesDir)) {
            if (!file.isFile || !file.canRead()) continue
            val parsed = runCatching { parse(file.readText(), file.parentFile) }.getOrDefault(emptyList())
            for (cfg in parsed) {
                if (seenIds.add(cfg.id)) result += cfg
            }
        }
        return result
    }

    fun parse(content: String, sshDir: File? = null): List<SshHostConfig> {
        val blocks = ArrayList<MutableBlock>()
        var current: MutableBlock? = null

        for (rawLine in content.lineSequence()) {
            val line = stripComment(rawLine).trim()
            if (line.isEmpty()) continue

            val (key, value) = splitKeyValue(line) ?: continue
            when (key.lowercase()) {
                "host" -> {
                    val aliases = value.split(Regex("\\s+")).filter { it.isNotEmpty() }
                    val alias = aliases.firstOrNull { !it.contains('*') && !it.contains('?') && !it.startsWith('!') }
                    current = if (alias != null) {
                        MutableBlock(alias).also { blocks += it }
                    } else {
                        null // Skip wildcard-only blocks like `Host *`
                    }
                }
                "match" -> current = null
                "hostname" -> current?.hostName = unquote(value)
                "user" -> current?.user = unquote(value)
                "port" -> current?.port = unquote(value).toIntOrNull()?.takeIf { it in 1..65535 }
                "identityfile" -> {
                    val path = unquote(value)
                    if (path.isNotEmpty() && current?.identityPath == null) {
                        current?.identityPath = path
                    }
                }
            }
        }

        return blocks.mapNotNull { b ->
            val host = (b.hostName ?: b.alias).trim()
            if (host.isEmpty()) return@mapNotNull null
            val keyText = b.identityPath?.let { resolveKeyFile(it, sshDir) }.orEmpty()
            SshHostConfig(
                id = CONFIG_ID_PREFIX + b.alias,
                label = b.alias,
                host = host,
                port = b.port ?: SshHostConfig.DEFAULT_PORT,
                username = b.user ?: "root",
                authType = if (keyText.isNotEmpty()) SshAuthType.PRIVATE_KEY else SshAuthType.PASSWORD,
                privateKey = keyText,
                rememberCredentials = true,
            )
        }
    }

    private fun resolveKeyFile(rawPath: String, sshDir: File?): String? {
        val homeDir = sshDir?.parentFile ?: Environment.getExternalStorageDirectory()
        val expanded = when {
            rawPath.startsWith("~/") -> File(homeDir, rawPath.removePrefix("~/"))
            rawPath.startsWith("/") -> File(rawPath)
            sshDir != null -> File(sshDir, rawPath)
            else -> File(homeDir, rawPath)
        }
        val candidates = listOfNotNull(
            expanded,
            // Fallback: `~/.ssh/id_ed25519` when `sshDir` is `/sdcard/.ssh` and `rawPath` is `/home/.../.ssh/id_ed25519`
            sshDir?.let { File(it, expanded.name) },
        )
        for (candidate in candidates) {
            if (candidate.isFile && candidate.canRead()) {
                return runCatching { candidate.readText() }.getOrNull()
            }
        }
        return null
    }

    private fun stripComment(line: String): String {
        var inQuotes = false
        for (i in line.indices) {
            val ch = line[i]
            if (ch == '"') inQuotes = !inQuotes
            else if (ch == '#' && !inQuotes) return line.substring(0, i)
        }
        return line
    }

    private fun splitKeyValue(line: String): Pair<String, String>? {
        val eqIdx = line.indexOf('=')
        val wsIdx = line.indexOfFirst { it.isWhitespace() }
        val splitIdx = when {
            eqIdx > 0 && (wsIdx < 0 || eqIdx < wsIdx) -> eqIdx
            wsIdx > 0 -> wsIdx
            else -> return null
        }
        val key = line.substring(0, splitIdx).trim()
        val value = line.substring(splitIdx + 1).trim()
        if (key.isEmpty() || value.isEmpty()) return null
        return key to value
    }

    private fun unquote(s: String): String =
        if (s.length >= 2 && s.startsWith('"') && s.endsWith('"')) s.substring(1, s.length - 1) else s

    private class MutableBlock(val alias: String) {
        var hostName: String? = null
        var user: String? = null
        var port: Int? = null
        var identityPath: String? = null
    }

    const val CONFIG_ID_PREFIX = "ssh-config:"
}
