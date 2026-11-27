package com.invictus.kodex.feature.ssh

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.invictus.kodex.KodexApp
import com.invictus.kodex.core.fs.FsResult
import com.invictus.kodex.core.ssh.SftpEntry
import com.invictus.kodex.core.ssh.SshConnectionManager
import com.invictus.kodex.core.ssh.SshConnectionState
import com.invictus.kodex.core.ssh.SshCredentialPrompt
import com.invictus.kodex.core.ssh.SshHostConfig
import com.invictus.kodex.core.ssh.SshHostKeyPrompt
import com.invictus.kodex.core.ssh.SshHostStore
import com.invictus.kodex.terminal.TerminalHub
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SshTestResult(
    val ok: Boolean,
    val message: String,
)

data class RemoteBrowserState(
    val currentPath: String,
    val entries: List<SftpEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

sealed interface SshEffect {
    data object OpenWorkspace : SshEffect
    data class Message(val text: String) : SshEffect
}

class SshViewModel(
    private val hostStore: SshHostStore,
    private val sshManager: SshConnectionManager,
) : ViewModel() {

    val hosts: StateFlow<List<SshHostConfig>> = hostStore.hosts
    val detectedHosts: StateFlow<List<SshHostConfig>> = hostStore.detectedHosts
    val connectionState: StateFlow<SshConnectionState> = sshManager.connectionState
    val hostKeyPrompt: StateFlow<SshHostKeyPrompt?> = sshManager.hostKeyPrompt
    val credentialPrompt: StateFlow<SshCredentialPrompt?> = sshManager.credentialPrompt

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    private val _testResult = MutableStateFlow<SshTestResult?>(null)
    val testResult: StateFlow<SshTestResult?> = _testResult.asStateFlow()

    private val _remoteBrowser = MutableStateFlow<RemoteBrowserState?>(null)
    val remoteBrowser: StateFlow<RemoteBrowserState?> = _remoteBrowser.asStateFlow()

    private val _effects = Channel<SshEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private var connectJob: kotlinx.coroutines.Job? = null

    fun refreshDetectedHosts() {
        hostStore.refreshDetectedHosts()
    }

    fun clearTestResult() {
        _testResult.value = null
    }

    fun saveHost(config: SshHostConfig) {
        hostStore.upsert(config)
    }

    fun deleteHost(id: String) {
        hostStore.delete(id)
    }

    fun testConnection(config: SshHostConfig) {
        if (_testing.value) return
        _testing.value = true
        _testResult.value = null
        viewModelScope.launch {
            val res = sshManager.testConnection(config)
            _testing.value = false
            _testResult.value = res.fold(
                onSuccess = { home -> SshTestResult(ok = true, message = "Connected (home: $home)") },
                onFailure = { err -> SshTestResult(ok = false, message = err.message ?: "Connection failed") },
            )
        }
    }

    fun connectAndOpenWorkspace(config: SshHostConfig, remotePathOverride: String? = null) {
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            val res = sshManager.connect(config, remotePathOverride)
            res.onSuccess {
                _effects.send(SshEffect.OpenWorkspace)
            }.onFailure { err ->
                _effects.send(SshEffect.Message(err.message ?: "SSH connection failed"))
            }
        }
    }

    fun cancelConnect() {
        connectJob?.cancel()
        connectJob = null
        sshManager.cancelConnect()
    }

    fun openSshTerminal(context: Context, config: SshHostConfig, remoteDir: String? = null) {
        if (hostStore.get(config.id) != null) {
            hostStore.markConnected(config.id)
        }
        val dir = remoteDir?.takeIf { it.isNotBlank() }
            ?: config.defaultRemotePath.takeIf { it.isNotBlank() }
        if (!TerminalHub.openSsh(context, config, sshManager, dir)) {
            _effects.trySend(SshEffect.Message("Maximum terminal tabs reached"))
        }
    }

    fun disconnect() {
        sshManager.disconnect()
    }

    fun respondHostKey(trust: Boolean) {
        hostKeyPrompt.value?.response?.complete(trust)
    }

    fun respondCredential(secret: String?) {
        credentialPrompt.value?.response?.complete(secret)
    }

    // region Remote folder browser (browse & pick folder after connecting)

    fun openRemoteBrowser() {
        val current = sshManager.currentConnected() ?: return
        loadRemoteBrowserPath(current.remoteRoot)
    }

    fun closeRemoteBrowser() {
        _remoteBrowser.value = null
    }

    fun navigateRemoteBrowser(path: String) {
        loadRemoteBrowserPath(path)
    }

    fun navigateRemoteBrowserUp() {
        val cur = _remoteBrowser.value?.currentPath ?: return
        val parent = SshConnectionManager.parentRemotePath(cur)
        if (parent != cur) loadRemoteBrowserPath(parent)
    }

    fun selectRemoteBrowserFolder() {
        val path = _remoteBrowser.value?.currentPath ?: return
        viewModelScope.launch {
            val res = sshManager.switchRemoteRoot(path)
            res.onSuccess {
                _remoteBrowser.value = null
                _effects.send(SshEffect.OpenWorkspace)
            }.onFailure { err ->
                _remoteBrowser.update { it?.copy(error = err.message ?: "Failed to open folder") }
            }
        }
    }

    private fun loadRemoteBrowserPath(path: String) {
        _remoteBrowser.value = RemoteBrowserState(currentPath = path, loading = true)
        viewModelScope.launch {
            when (val res = sshManager.list(path)) {
                is FsResult.Ok -> {
                    _remoteBrowser.value = RemoteBrowserState(
                        currentPath = path,
                        entries = res.value.filter { it.isDirectory },
                        loading = false,
                    )
                }
                is FsResult.Err -> {
                    _remoteBrowser.value = RemoteBrowserState(
                        currentPath = path,
                        loading = false,
                        error = res.detail ?: res.error.name,
                    )
                }
            }
        }
    }

    // endregion

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KodexApp
                SshViewModel(
                    hostStore = app.container.sshHostStore,
                    sshManager = app.container.sshConnectionManager,
                )
            }
        }
    }
}
