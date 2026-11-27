package com.invictus.kodex.core.ssh

import android.content.Context
import android.util.Log
import com.invictus.kodex.core.fs.FsError
import com.invictus.kodex.core.fs.FsResult
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Manages the active SSH/SFTP workspace connection and provides SFTP file operations,
 * local cache mirror mapping, and interactive SSH shell channels.
 */
class SshConnectionManager(
    context: Context,
    private val hostStore: SshHostStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext
    private val workspacesDir = File(appContext.cacheDir, "ssh_workspaces")

    private val _connectionState = MutableStateFlow<SshConnectionState>(SshConnectionState.Disconnected)
    val connectionState: StateFlow<SshConnectionState> = _connectionState.asStateFlow()

    private val _hostKeyPrompt = MutableStateFlow<SshHostKeyPrompt?>(null)
    val hostKeyPrompt: StateFlow<SshHostKeyPrompt?> = _hostKeyPrompt.asStateFlow()

    private val _credentialPrompt = MutableStateFlow<SshCredentialPrompt?>(null)
    val credentialPrompt: StateFlow<SshCredentialPrompt?> = _credentialPrompt.asStateFlow()

    private val sessionLock = Any()
    private val sftpLock = Any()
    private val reconnectMutex = Mutex()

    private var activeSession: Session? = null
    private var activeJumpSessions: List<Session> = emptyList()
    private var activeSftp: ChannelSftp? = null
    private var activeHost: SshHostConfig? = null
    private var activeMirrorRoot: File? = null
    private var lastConnectedHost: SshHostConfig? = null
    private var lastRemoteRoot: String? = null
    @Volatile private var pendingSession: Session? = null
    @Volatile private var pendingSocket: java.net.Socket? = null

    val isConnected: Boolean
        get() = _connectionState.value is SshConnectionState.Connected

    fun currentConnected(): SshConnectionState.Connected? =
        _connectionState.value as? SshConnectionState.Connected

    fun mirrorRootFor(hostId: String): File = File(workspacesDir, hostId)

    fun localFileFor(remotePath: String): File? {
        val mirror = activeMirrorRoot ?: return null
        val normalized = normalizeRemotePath(remotePath).trimStart('/')
        return if (normalized.isEmpty()) mirror else File(mirror, normalized)
    }

    fun remotePathFor(localFile: File): String? {
        val mirror = activeMirrorRoot ?: return null
        val mirrorPath = mirror.absolutePath.trimEnd('/')
        val filePath = localFile.absolutePath
        if (filePath == mirrorPath) return "/"
        if (!filePath.startsWith("$mirrorPath/")) return null
        val rel = filePath.removePrefix(mirrorPath)
        return if (rel.startsWith("/")) rel else "/$rel"
    }

    fun isMirrorFile(localFile: File): Boolean = remotePathFor(localFile) != null

    /**
     * Connects to [host], opens an SFTP channel, resolves [remotePathOverride] or
     * [SshHostConfig.defaultRemotePath] (falling back to remote `$HOME`), and sets
     * [connectionState] to [SshConnectionState.Connected].
     */
    suspend fun connect(
        host: SshHostConfig,
        remotePathOverride: String? = null,
    ): Result<SshConnectionState.Connected> = reconnectMutex.withLock {
        connectInternal(host, remotePathOverride)
    }

    private suspend fun connectInternal(
        host: SshHostConfig,
        remotePathOverride: String? = null,
    ): Result<SshConnectionState.Connected> = withContext(io) {
        closeActiveChannelsOnly()
        _connectionState.value = SshConnectionState.Connecting(host, "Connecting to ${host.endpoint}...")
        var bundle: SessionBundle? = null
        var sftp: ChannelSftp? = null
        try {
            bundle = openSessionBundle(host, socketTimeoutMs = SOCKET_TIMEOUT_MS)
            val session = bundle.session
            pendingSession = session
            _connectionState.value = SshConnectionState.Connecting(host, "Opening SFTP subsystem...")
            sftp = (session.openChannel("sftp") as ChannelSftp).apply {
                connect(CHANNEL_TIMEOUT_MS)
            }
            val requested = (remotePathOverride ?: host.defaultRemotePath).trim()
            val resolvedRoot = resolveRemoteDir(sftp, requested)
            val mirrorRoot = mirrorRootFor(host.id).apply { mkdirs() }
            localFileFor(mirrorRoot, resolvedRoot).mkdirs()

            synchronized(sessionLock) {
                activeSession = session
                activeJumpSessions = bundle.jumpSessions
                activeSftp = sftp
                activeHost = host
                activeMirrorRoot = mirrorRoot
                lastConnectedHost = host
                lastRemoteRoot = resolvedRoot
                pendingSession = null
                pendingSocket = null
            }
            if (hostStore.get(host.id) != null) {
                hostStore.markConnected(host.id)
                hostStore.setActiveSession(host.id, resolvedRoot)
            }
            val connected = SshConnectionState.Connected(host, resolvedRoot, mirrorRoot)
            _connectionState.value = connected
            Result.success(connected)
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { sftp?.disconnect() }
            bundle?.disconnectAll()
            pendingSession = null
            pendingSocket = null
            _connectionState.value = SshConnectionState.Disconnected
            throw e
        } catch (e: Exception) {
            runCatching { sftp?.disconnect() }
            bundle?.disconnectAll()
            pendingSession = null
            pendingSocket = null
            Log.w(TAG, "SSH connect failed", e)
            val msg = friendlyError(e)
            _connectionState.value = SshConnectionState.Error(host, msg)
            Result.failure(e)
        }
    }

    fun hasPersistedActiveSession(): Boolean {
        val saved = hostStore.getActiveSession() ?: return false
        return hostStore.get(saved.hostId) != null
    }

    /**
     * Automatically reconnects if the active SSH/SFTP session was dropped by Android
     * (e.g. backgrounding / network switch) or after a process restart.
     */
    suspend fun reconnectIfNeeded(): Boolean = reconnectMutex.withLock {
        val live = synchronized(sessionLock) {
            activeSession?.isConnected == true && activeSftp?.isConnected == true
        }
        if (live) return@withLock true

        val inMemoryHost = lastConnectedHost
        val inMemoryRoot = lastRemoteRoot
        if (inMemoryHost != null) {
            return@withLock connectInternal(inMemoryHost, inMemoryRoot).isSuccess
        }

        val saved = hostStore.getActiveSession() ?: return@withLock false
        val host = hostStore.get(saved.hostId) ?: run {
            hostStore.clearActiveSession()
            return@withLock false
        }
        return@withLock connectInternal(host, saved.remoteRoot).isSuccess
    }

    fun cancelConnect() {
        runCatching { pendingSocket?.close() }
        runCatching { pendingSession?.disconnect() }
        pendingSocket = null
        pendingSession = null
        if (_connectionState.value is SshConnectionState.Connecting) {
            _connectionState.value = SshConnectionState.Disconnected
        }
    }

    /** Tests connectivity + SFTP without disturbing the active workspace connection if possible. */
    suspend fun testConnection(host: SshHostConfig): Result<String> = withContext(io) {
        var bundle: SessionBundle? = null
        var sftp: ChannelSftp? = null
        try {
            bundle = openSessionBundle(host, socketTimeoutMs = SOCKET_TIMEOUT_MS)
            sftp = (bundle.session.openChannel("sftp") as ChannelSftp).apply { connect(CHANNEL_TIMEOUT_MS) }
            val home = sftp.pwd()
            Result.success(home)
        } catch (e: Exception) {
            Result.failure(Exception(friendlyError(e), e))
        } finally {
            runCatching { sftp?.disconnect() }
            bundle?.disconnectAll()
        }
    }

    /** Changes the active remote workspace folder on the already-connected host. */
    suspend fun switchRemoteRoot(remotePath: String): Result<String> = withContext(io) {
        val current = currentConnected()
            ?: return@withContext Result.failure(IllegalStateException("Not connected"))
        try {
            val sftp = ensureSftpConnected()
            val resolved = synchronized(sftpLock) { resolveRemoteDir(sftp, remotePath) }
            localFileFor(current.mirrorRoot, resolved).mkdirs()
            lastRemoteRoot = resolved
            if (hostStore.get(current.host.id) != null) {
                hostStore.setActiveSession(current.host.id, resolved)
            }
            _connectionState.value = current.copy(remoteRoot = resolved)
            Result.success(resolved)
        } catch (e: Exception) {
            Result.failure(Exception(friendlyError(e), e))
        }
    }

    fun disconnect() {
        closeActiveChannelsOnly()
        lastConnectedHost = null
        lastRemoteRoot = null
        hostStore.clearActiveSession()
        _connectionState.value = SshConnectionState.Disconnected
    }

    private fun closeActiveChannelsOnly() {
        synchronized(sessionLock) {
            runCatching { activeSftp?.disconnect() }
            runCatching { activeSession?.disconnect() }
            activeJumpSessions.asReversed().forEach { runCatching { it.disconnect() } }
            activeSftp = null
            activeSession = null
            activeJumpSessions = emptyList()
            activeHost = null
            activeMirrorRoot = null
        }
    }

    // region SFTP file operations

    suspend fun list(remotePath: String): FsResult<List<SftpEntry>> = withContext(io) {
        guardSftp { sftp ->
            val norm = normalizeRemotePath(remotePath)
            @Suppress("UNCHECKED_CAST")
            val raw = sftp.ls(norm) as VectorLike
            val entries = ArrayList<SftpEntry>(raw.size)
            for (item in raw) {
                val entry = item as ChannelSftp.LsEntry
                val name = entry.filename
                if (name == "." || name == "..") continue
                val attrs = entry.attrs
                val isDir = resolveIsDir(sftp, norm, name, attrs)
                val fullPath = joinRemotePath(norm, name)
                entries += SftpEntry(
                    name = name,
                    remotePath = fullPath,
                    isDirectory = isDir,
                    size = attrs.size,
                    modifiedMs = attrs.mTime.toLong() * 1000L,
                )
            }
            entries.sortedWith(
                compareByDescending<SftpEntry> { it.isDirectory }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
            )
        }
    }

    suspend fun downloadToMirror(remotePath: String, localFile: File): Result<File> = withContext(io) {
        try {
            val sftp = ensureSftpConnected()
            localFile.parentFile?.mkdirs()
            val tmp = File(localFile.parentFile, "${localFile.name}.sshtmp")
            synchronized(sftpLock) {
                tmp.outputStream().use { out ->
                    sftp.get(normalizeRemotePath(remotePath), out)
                }
            }
            if (!tmp.renameTo(localFile)) {
                tmp.copyTo(localFile, overwrite = true)
                tmp.delete()
            }
            Result.success(localFile)
        } catch (e: Exception) {
            Log.w(TAG, "SFTP download failed: $remotePath", e)
            Result.failure(Exception(friendlyError(e), e))
        }
    }

    suspend fun uploadFromMirror(localFile: File, remotePath: String): Result<Unit> = withContext(io) {
        try {
            val sftp = ensureSftpConnected()
            synchronized(sftpLock) {
                localFile.inputStream().use { input ->
                    sftp.put(input, normalizeRemotePath(remotePath), ChannelSftp.OVERWRITE)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "SFTP upload failed: $remotePath", e)
            Result.failure(Exception(friendlyError(e), e))
        }
    }

    suspend fun createFile(remoteParent: String, rawName: String): FsResult<String> = withContext(io) {
        val name = validateName(rawName) ?: return@withContext FsResult.Err(FsError.INVALID_NAME)
        val target = joinRemotePath(normalizeRemotePath(remoteParent), name)
        guardSftp { sftp ->
            if (exists(sftp, target)) return@guardSftp throw AlreadyExistsSignal()
            java.io.ByteArrayInputStream(ByteArray(0)).use { empty ->
                sftp.put(empty, target, ChannelSftp.OVERWRITE)
            }
            target
        }
    }

    suspend fun createFolder(remoteParent: String, rawName: String): FsResult<String> = withContext(io) {
        val name = validateName(rawName) ?: return@withContext FsResult.Err(FsError.INVALID_NAME)
        val target = joinRemotePath(normalizeRemotePath(remoteParent), name)
        guardSftp { sftp ->
            if (exists(sftp, target)) return@guardSftp throw AlreadyExistsSignal()
            sftp.mkdir(target)
            target
        }
    }

    suspend fun rename(oldRemotePath: String, rawName: String): FsResult<String> = withContext(io) {
        val name = validateName(rawName) ?: return@withContext FsResult.Err(FsError.INVALID_NAME)
        val oldNorm = normalizeRemotePath(oldRemotePath)
        val parent = parentRemotePath(oldNorm)
        val target = joinRemotePath(parent, name)
        if (target == oldNorm) return@withContext FsResult.Ok(target)
        guardSftp { sftp ->
            if (exists(sftp, target)) throw AlreadyExistsSignal()
            sftp.rename(oldNorm, target)
            target
        }
    }

    suspend fun delete(remotePath: String, isDirectory: Boolean): FsResult<Unit> = withContext(io) {
        val norm = normalizeRemotePath(remotePath)
        guardSftp { sftp ->
            if (isDirectory) deleteDirRecursive(sftp, norm) else sftp.rm(norm)
        }
    }

    suspend fun duplicate(remotePath: String, isDirectory: Boolean): FsResult<String> = withContext(io) {
        val norm = normalizeRemotePath(remotePath)
        val parent = parentRemotePath(norm)
        val name = norm.substringAfterLast('/')
        guardSftp { sftp ->
            val target = uniqueRemoteName(sftp, parent, name, isDirectory)
            if (isDirectory) {
                copyDirRecursive(sftp, norm, target)
            } else {
                val bytes = java.io.ByteArrayOutputStream()
                sftp.get(norm, bytes)
                java.io.ByteArrayInputStream(bytes.toByteArray()).use { input ->
                    sftp.put(input, target, ChannelSftp.OVERWRITE)
                }
            }
            target
        }
    }

    // endregion

    // region SSH Shell for Terminal

    /**
     * Opens an interactive PTY shell channel on [host]. Reuses [activeSession] if connected
     * to the same host, otherwise opens a dedicated session that closes when the shell closes.
     */
    @Throws(Exception::class)
    fun openShell(
        host: SshHostConfig,
        cols: Int,
        rows: Int,
        initialDir: String? = null,
    ): SshShellHandle {
        val reuse = synchronized(sessionLock) {
            val s = activeSession
            if (s != null && s.isConnected && activeHost?.id == host.id) s else null
        }
        val ownedBundle = if (reuse == null) openSessionBundle(host, socketTimeoutMs = 0) else null
        val session = reuse ?: ownedBundle!!.session
        val channel = (session.openChannel("shell") as ChannelShell).apply {
            setPty(true)
            setPtyType("xterm-256color")
            setPtySize(cols.coerceAtLeast(20), rows.coerceAtLeast(5), 0, 0)
            connect(CHANNEL_TIMEOUT_MS)
        }
        val out = channel.outputStream
        val input = channel.inputStream
        if (!initialDir.isNullOrBlank()) {
            val escaped = initialDir.replace("'", "'\\''")
            runCatching {
                out.write("cd '$escaped' && clear\n".toByteArray(Charsets.UTF_8))
                out.flush()
            }
        }
        return object : SshShellHandle {
            override val inputStream: InputStream = input
            override val outputStream: OutputStream = out
            override fun resize(cols: Int, rows: Int) {
                runCatching { channel.setPtySize(cols.coerceAtLeast(20), rows.coerceAtLeast(5), 0, 0) }
            }
            override fun isConnected(): Boolean = channel.isConnected && session.isConnected
            override fun exitStatus(): Int = channel.exitStatus
            override fun close() {
                runCatching { channel.disconnect() }
                ownedBundle?.disconnectAll()
            }
        }
    }

    // endregion

    // region JSch session setup

    private class SessionBundle(val session: Session, val jumpSessions: List<Session>) {
        fun disconnectAll() {
            runCatching { session.disconnect() }
            jumpSessions.asReversed().forEach { runCatching { it.disconnect() } }
        }
    }

    private fun openSessionBundle(host: SshHostConfig, socketTimeoutMs: Int = SOCKET_TIMEOUT_MS): SessionBundle {
        val jumpSpecs = host.proxyJump.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val jumpSessions = ArrayList<Session>(jumpSpecs.size)
        try {
            var connectHost = host.host.trim()
            var connectPort = host.port
            var hostKeyAlias: String? = null

            for (spec in jumpSpecs) {
                val hopConfig = resolveJumpHost(spec, defaultUser = host.username)
                val hopSession = openSingleSession(
                    host = hopConfig,
                    connectHost = connectHost,
                    connectPort = connectPort,
                    hostKeyAlias = hostKeyAlias,
                    socketTimeoutMs = socketTimeoutMs,
                )
                jumpSessions += hopSession
                // Forward an ephemeral local port through this jump host to the next hop (or final target).
                val nextTargetHost = if (jumpSessions.size < jumpSpecs.size) {
                    resolveJumpHost(jumpSpecs[jumpSessions.size], defaultUser = host.username).host.trim()
                } else {
                    host.host.trim()
                }
                val nextTargetPort = if (jumpSessions.size < jumpSpecs.size) {
                    resolveJumpHost(jumpSpecs[jumpSessions.size], defaultUser = host.username).port
                } else {
                    host.port
                }
                connectPort = hopSession.setPortForwardingL(0, nextTargetHost, nextTargetPort)
                connectHost = "127.0.0.1"
                hostKeyAlias = nextTargetHost
            }

            val finalSession = openSingleSession(
                host = host,
                connectHost = connectHost,
                connectPort = connectPort,
                hostKeyAlias = hostKeyAlias,
                socketTimeoutMs = socketTimeoutMs,
            )
            applyPortForwards(finalSession, host.portForwards)
            return SessionBundle(finalSession, jumpSessions)
        } catch (e: Exception) {
            jumpSessions.asReversed().forEach { runCatching { it.disconnect() } }
            throw e
        }
    }

    private fun resolveJumpHost(spec: String, defaultUser: String): SshHostConfig {
        val savedMatch = hostStore.hosts.value.firstOrNull {
            it.label.equals(spec, ignoreCase = true) || it.host.equals(spec, ignoreCase = true)
        }
        if (savedMatch != null) return savedMatch
        val detectedMatch = hostStore.detectedHosts.value.firstOrNull {
            it.label.equals(spec, ignoreCase = true) || it.id == SshConfigParser.CONFIG_ID_PREFIX + spec
        }
        if (detectedMatch != null) return detectedMatch
        return SshQuickConnectParser.parse(spec, defaultUser = defaultUser.ifBlank { "root" })
            ?: throw IllegalArgumentException("Invalid ProxyJump host: $spec")
    }

    private fun applyPortForwards(session: Session, rules: List<SshForwardRule>) {
        for (rule in rules) {
            val bind = rule.bindAddress.ifBlank { "127.0.0.1" }
            when (rule.type) {
                SshForwardType.LOCAL -> session.setPortForwardingL(bind, rule.listenPort, rule.targetHost, rule.targetPort)
                SshForwardType.REMOTE -> session.setPortForwardingR(bind, rule.listenPort, rule.targetHost, rule.targetPort)
            }
        }
    }

    private fun openSingleSession(
        host: SshHostConfig,
        connectHost: String,
        connectPort: Int,
        hostKeyAlias: String?,
        socketTimeoutMs: Int,
    ): Session {
        val jsch = JSch()
        hostStore.knownHostsFile.parentFile?.mkdirs()
        if (!hostStore.knownHostsFile.exists()) {
            hostStore.knownHostsFile.createNewFile()
        }
        jsch.setKnownHosts(hostStore.knownHostsFile.absolutePath)

        var resolvedPassphrase = host.passphrase
        if (host.authType == SshAuthType.PRIVATE_KEY) {
            val keyText = host.privateKey.trim()
            if (keyText.isEmpty()) throw IllegalArgumentException("Private key is empty")
            val keyBytes = (keyText + "\n").toByteArray(Charsets.UTF_8)
            if (resolvedPassphrase.isNotEmpty()) {
                jsch.addIdentity("kodex-${host.id}", keyBytes, null, resolvedPassphrase.toByteArray(Charsets.UTF_8))
            } else {
                jsch.addIdentity("kodex-${host.id}", keyBytes, null, null)
            }
        }

        val session = jsch.getSession(host.username.trim(), connectHost, connectPort)
        if (!hostKeyAlias.isNullOrBlank()) {
            session.setHostKeyAlias(hostKeyAlias)
        }
        if (host.authType == SshAuthType.PASSWORD && host.password.isNotEmpty()) {
            session.setPassword(host.password)
        }
        session.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
        session.setConfig("StrictHostKeyChecking", "ask")
        session.setSocketFactory(object : com.jcraft.jsch.SocketFactory {
            override fun createSocket(hostName: String, port: Int): java.net.Socket {
                val sock = java.net.Socket()
                pendingSocket = sock
                sock.tcpNoDelay = true
                if (socketTimeoutMs > 0) sock.soTimeout = socketTimeoutMs
                sock.connect(java.net.InetSocketAddress(hostName, port), CONNECT_TIMEOUT_MS)
                return sock
            }
            override fun getInputStream(socket: java.net.Socket): InputStream = socket.getInputStream()
            override fun getOutputStream(socket: java.net.Socket): OutputStream = socket.getOutputStream()
        })
        if (socketTimeoutMs > 0) session.timeout = socketTimeoutMs
        session.setServerAliveInterval(KEEPALIVE_INTERVAL_MS)
        session.setServerAliveCountMax(2)
        session.userInfo = InteractiveUserInfo(host)
        pendingSession = session
        session.connect(CONNECT_TIMEOUT_MS)
        pendingSession = null
        pendingSocket = null
        return session
    }

    private inner class InteractiveUserInfo(private val host: SshHostConfig) : UserInfo {
        private var enteredPassword: String? = host.password.takeIf { it.isNotEmpty() }
        private var enteredPassphrase: String? = host.passphrase.takeIf { it.isNotEmpty() }

        override fun getPassphrase(): String? = enteredPassphrase
        override fun getPassword(): String? = enteredPassword

        override fun promptPassword(message: String?): Boolean {
            if (!enteredPassword.isNullOrEmpty()) return true
            val result = promptCredential(host, message ?: "Password for ${host.endpoint}", isPassphrase = false)
            if (result != null) {
                enteredPassword = result
                return true
            }
            return false
        }

        override fun promptPassphrase(message: String?): Boolean {
            if (!enteredPassphrase.isNullOrEmpty()) return true
            val result = promptCredential(host, message ?: "Passphrase for private key", isPassphrase = true)
            if (result != null) {
                enteredPassphrase = result
                return true
            }
            return false
        }

        override fun promptYesNo(message: String?): Boolean {
            val msg = message.orEmpty()
            val isMismatch = msg.contains("WARNING", ignoreCase = true) ||
                msg.contains("CHANGED", ignoreCase = true)
            val fpLine = msg.lines().firstOrNull { it.contains("fingerprint", ignoreCase = true) }.orEmpty()
            val keyType = fpLine.substringBefore("key", "").trim().ifEmpty { "SSH" }
            val fingerprint = fpLine.substringAfter("is ", fpLine).substringBefore(".").trim()
            val deferred = CompletableDeferred<Boolean>()
            _hostKeyPrompt.value = SshHostKeyPrompt(
                host = host.host,
                port = host.port,
                keyType = keyType,
                fingerprint = fingerprint.ifEmpty { msg },
                isMismatch = isMismatch,
                response = deferred,
            )
            return try {
                runBlocking { deferred.await() }
            } finally {
                _hostKeyPrompt.value = null
            }
        }

        override fun showMessage(message: String?) {
            Log.i(TAG, "SSH message: $message")
        }
    }

    private fun promptCredential(host: SshHostConfig, label: String, isPassphrase: Boolean): String? {
        val deferred = CompletableDeferred<String?>()
        _credentialPrompt.value = SshCredentialPrompt(host, label, isPassphrase, deferred)
        return try {
            runBlocking { deferred.await() }
        } finally {
            _credentialPrompt.value = null
        }
    }

    // endregion

    // region helpers

    private suspend fun ensureSftpConnected(): ChannelSftp {
        val live = synchronized(sessionLock) {
            val sftp = activeSftp
            val sess = activeSession
            if (sftp != null && sftp.isConnected && sess != null && sess.isConnected) sftp else null
        }
        if (live != null) return live
        if (reconnectIfNeeded()) {
            val reconnected = synchronized(sessionLock) { activeSftp }
            if (reconnected != null && reconnected.isConnected) return reconnected
        }
        throw IllegalStateException("SSH SFTP channel is not connected")
    }

    private suspend inline fun <T> guardSftp(crossinline block: (ChannelSftp) -> T): FsResult<T> = try {
        val sftp = ensureSftpConnected()
        val value = synchronized(sftpLock) { block(sftp) }
        FsResult.Ok(value)
    } catch (_: AlreadyExistsSignal) {
        FsResult.Err(FsError.ALREADY_EXISTS)
    } catch (e: SftpException) {
        when (e.id) {
            ChannelSftp.SSH_FX_NO_SUCH_FILE -> FsResult.Err(FsError.NOT_FOUND, e.message)
            ChannelSftp.SSH_FX_PERMISSION_DENIED -> FsResult.Err(FsError.PERMISSION, e.message)
            else -> FsResult.Err(FsError.IO, e.message)
        }
    } catch (e: Exception) {
        FsResult.Err(FsError.IO, friendlyError(e))
    }

    private fun resolveRemoteDir(sftp: ChannelSftp, requested: String): String {
        val home = sftp.pwd()
        val target = when {
            requested.isEmpty() || requested == "~" -> home
            requested.startsWith("~/") -> joinRemotePath(home, requested.removePrefix("~/"))
            else -> normalizeRemotePath(requested)
        }
        val attrs = sftp.stat(target)
        if (!attrs.isDir) throw IllegalArgumentException("Not a directory: $target")
        return sftp.realpath(target)
    }

    private fun resolveIsDir(sftp: ChannelSftp, parent: String, name: String, attrs: SftpATTRS): Boolean {
        if (attrs.isDir) return true
        if (attrs.isLink) {
            return runCatching { sftp.stat(joinRemotePath(parent, name)).isDir }.getOrDefault(false)
        }
        return false
    }

    private fun exists(sftp: ChannelSftp, path: String): Boolean =
        runCatching { sftp.lstat(path); true }.getOrDefault(false)

    private fun deleteDirRecursive(sftp: ChannelSftp, dir: String) {
        @Suppress("UNCHECKED_CAST")
        val children = sftp.ls(dir) as java.util.Vector<ChannelSftp.LsEntry>
        for (child in children) {
            val name = child.filename
            if (name == "." || name == "..") continue
            val childPath = joinRemotePath(dir, name)
            if (child.attrs.isDir && !child.attrs.isLink) {
                deleteDirRecursive(sftp, childPath)
            } else {
                sftp.rm(childPath)
            }
        }
        sftp.rmdir(dir)
    }

    private fun copyDirRecursive(sftp: ChannelSftp, src: String, dst: String) {
        sftp.mkdir(dst)
        @Suppress("UNCHECKED_CAST")
        val children = sftp.ls(src) as java.util.Vector<ChannelSftp.LsEntry>
        for (child in children) {
            val name = child.filename
            if (name == "." || name == "..") continue
            val srcChild = joinRemotePath(src, name)
            val dstChild = joinRemotePath(dst, name)
            if (child.attrs.isDir && !child.attrs.isLink) {
                copyDirRecursive(sftp, srcChild, dstChild)
            } else {
                val bytes = java.io.ByteArrayOutputStream()
                sftp.get(srcChild, bytes)
                java.io.ByteArrayInputStream(bytes.toByteArray()).use { input ->
                    sftp.put(input, dstChild, ChannelSftp.OVERWRITE)
                }
            }
        }
    }

    private fun uniqueRemoteName(sftp: ChannelSftp, parent: String, name: String, isDir: Boolean): String {
        val dot = if (isDir) -1 else name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (true) {
            val suffix = if (n == 1) " copy" else " copy $n"
            val candidate = joinRemotePath(parent, "$stem$suffix$ext")
            if (!exists(sftp, candidate)) return candidate
            n++
        }
    }

    private fun localFileFor(mirrorRoot: File, remotePath: String): File {
        val rel = normalizeRemotePath(remotePath).trimStart('/')
        return if (rel.isEmpty()) mirrorRoot else File(mirrorRoot, rel)
    }

    private fun validateName(raw: String): String? {
        val name = raw.trim()
        if (name.isEmpty() || name == "." || name == "..") return null
        if ('/' in name || '\\' in name || '\u0000' in name) return null
        return name
    }

    private fun friendlyError(e: Exception): String {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("Auth fail", ignoreCase = true) -> "Authentication failed (check username, password, or key)"
            msg.contains("Auth cancel", ignoreCase = true) -> "Authentication cancelled"
            msg.contains("UnknownHostException", ignoreCase = true) -> "Could not resolve host"
            msg.contains("ECONNREFUSED", ignoreCase = true) || msg.contains("Connection refused", ignoreCase = true) ->
                "Connection refused (is sshd running on this port?)"
            msg.contains("timeout", ignoreCase = true) || msg.contains("timed out", ignoreCase = true) ->
                "Connection timed out"
            msg.contains("reject HostKey", ignoreCase = true) -> "Host key verification rejected"
            else -> msg.ifBlank { e.javaClass.simpleName }
        }
    }

    private class AlreadyExistsSignal : Exception()

    // endregion

    companion object {
        private const val TAG = "SshConnectionManager"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val SOCKET_TIMEOUT_MS = 15_000
        private const val CHANNEL_TIMEOUT_MS = 10_000
        private const val KEEPALIVE_INTERVAL_MS = 10_000
        private const val WORKSPACE_DIR_SEGMENT = "/ssh_workspaces/"

        fun parseMirrorPath(localPath: String): Pair<String, String>? {
            val idx = localPath.indexOf(WORKSPACE_DIR_SEGMENT)
            if (idx < 0) return null
            val rest = localPath.substring(idx + WORKSPACE_DIR_SEGMENT.length)
            val slash = rest.indexOf('/')
            if (slash <= 0) {
                val hostId = rest.trimEnd('/')
                return if (hostId.isNotEmpty()) hostId to "/" else null
            }
            val hostId = rest.substring(0, slash)
            val remotePath = "/" + rest.substring(slash + 1).trimStart('/')
            return hostId to remotePath
        }

        fun formatMirrorDisplayPath(context: Context, localPath: String): String? {
            val (hostId, remotePath) = parseMirrorPath(localPath) ?: return null
            val host = (context.applicationContext as? com.invictus.kodex.KodexApp)
                ?.container?.sshHostStore?.get(hostId)
            return if (host != null) "SSH: ${host.displayName} • $remotePath" else "SSH • $remotePath"
        }

        fun normalizeRemotePath(path: String): String {
            val clean = path.trim().replace('\\', '/')
            if (clean.isEmpty() || clean == "/") return "/"
            return "/" + clean.split('/').filter { it.isNotEmpty() }.joinToString("/")
        }

        fun joinRemotePath(parent: String, name: String): String {
            val p = normalizeRemotePath(parent)
            return if (p == "/") "/$name" else "$p/$name"
        }

        fun parentRemotePath(path: String): String {
            val p = normalizeRemotePath(path)
            if (p == "/") return "/"
            val idx = p.lastIndexOf('/')
            return if (idx <= 0) "/" else p.substring(0, idx)
        }
    }
}

private typealias VectorLike = java.util.Vector<*>
