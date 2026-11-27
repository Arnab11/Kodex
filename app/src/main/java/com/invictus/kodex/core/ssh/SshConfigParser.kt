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
                "proxyjump" -> {
                    val jump = unquote(value)
                    current?.proxyJump = if (jump.equals("none", ignoreCase = true)) "" else jump
                }
                "localforward" -> {
                    parseConfigForward(SshForwardType.LOCAL, value)?.let { rule ->
                        current?.forwards?.add(rule)
                    }
                }
                "remoteforward" -> {
                    parseConfigForward(SshForwardType.REMOTE, value)?.let { rule ->
                        current?.forwards?.add(rule)
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
                proxyJump = b.proxyJump.orEmpty(),
                portForwards = b.forwards.toList(),
                rememberCredentials = true,
            )
        }
    }

    /** Parses OpenSSH config `LocalForward [bind:]port host:hostport` / `RemoteForward ...`. */
    fun parseConfigForward(type: SshForwardType, raw: String): SshForwardRule? {
        val parts = raw.trim().split(Regex("\\s+"))
        if (parts.size < 2) return null
        val (bindAddr, listenPort) = parseHostPort(parts[0], defaultHost = "127.0.0.1") ?: return null
        val (targetHost, targetPort) = parseHostPort(parts[1], defaultHost = null) ?: return null
        return SshForwardRule(
            type = type,
            bindAddress = bindAddr,
            listenPort = listenPort,
            targetHost = targetHost,
            targetPort = targetPort,
        )
    }

    /**
     * Parses UI / compact forward specs separated by commas or newlines:
     * `L 8080:localhost:80, R 9000:127.0.0.1:3000` or `8080:localhost:80` (defaults to Local).
     */
    fun parseForwardList(raw: String): List<SshForwardRule> {
        if (raw.isBlank()) return emptyList()
        return raw.split(',', '\n').mapNotNull { entry ->
            val trimmed = entry.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val tokens = trimmed.split(Regex("\\s+"), limit = 2)
            val (type, spec) = when (tokens[0].uppercase()) {
                "L", "LOCAL" -> SshForwardType.LOCAL to tokens.getOrNull(1).orEmpty().trim()
                "R", "REMOTE" -> SshForwardType.REMOTE to tokens.getOrNull(1).orEmpty().trim()
                else -> SshForwardType.LOCAL to trimmed
            }
            if (spec.isEmpty()) return@mapNotNull null
            // Support either `8080:host:80` / `127.0.0.1:8080:host:80` or `8080 host:80`
            if (spec.contains(' ')) {
                parseConfigForward(type, spec)
            } else {
                val colonParts = spec.split(':')
                when (colonParts.size) {
                    3 -> {
                        val lp = colonParts[0].toIntOrNull()?.takeIf { it in 1..65535 } ?: return@mapNotNull null
                        val tp = colonParts[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return@mapNotNull null
                        val th = colonParts[1].trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        SshForwardRule(type, "127.0.0.1", lp, th, tp)
                    }
                    4 -> {
                        val bind = colonParts[0].trim().ifEmpty { "127.0.0.1" }
                        val lp = colonParts[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return@mapNotNull null
                        val th = colonParts[2].trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        val tp = colonParts[3].toIntOrNull()?.takeIf { it in 1..65535 } ?: return@mapNotNull null
                        SshForwardRule(type, bind, lp, th, tp)
                    }
                    else -> null
                }
            }
        }
    }

    private fun parseHostPort(token: String, defaultHost: String?): Pair<String, Int>? {
        val clean = unquote(token.trim())
        if (clean.isEmpty()) return null
        if (clean.startsWith("[")) {
            val close = clean.indexOf(']')
            if (close < 0) return null
            val host = clean.substring(1, close)
            val rest = clean.substring(close + 1)
            if (!rest.startsWith(":")) return null
            val port = rest.substring(1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return host to port
        }
        val colon = clean.lastIndexOf(':')
        if (colon < 0) {
            if (defaultHost == null) return null
            val port = clean.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return defaultHost to port
        }
        val host = clean.substring(0, colon).ifEmpty { defaultHost ?: return null }
        val port = clean.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return host to port
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
        var proxyJump: String? = null
        val forwards = ArrayList<SshForwardRule>()
    }

    const val CONFIG_ID_PREFIX = "ssh-config:"
}
