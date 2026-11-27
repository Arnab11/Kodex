package com.invictus.kodex.core.ssh

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted store for saved SSH host profiles (AES-256-GCM backed by Android Keystore).
 * Secrets (passwords, private keys, passphrases) never hit plain storage or logs.
 */
class SshHostStore(context: Context) {

    data class ActiveSshSession(val hostId: String, val remoteRoot: String)

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)
    private val sessionPrefs = appContext.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
    val knownHostsFile: File = File(appContext.filesDir, KNOWN_HOSTS_FILE)

    private val _hosts = MutableStateFlow<List<SshHostConfig>>(emptyList())
    val hosts: StateFlow<List<SshHostConfig>> = _hosts.asStateFlow()

    private val _detectedHosts = MutableStateFlow<List<SshHostConfig>>(emptyList())
    val detectedHosts: StateFlow<List<SshHostConfig>> = _detectedHosts.asStateFlow()

    init {
        _hosts.value = readAll()
        refreshDetectedHosts()
    }

    fun refreshDetectedHosts() {
        _detectedHosts.value = SshConfigParser.loadFromDefaultLocations(appContext.filesDir)
    }

    @Synchronized
    fun get(id: String): SshHostConfig? =
        _hosts.value.firstOrNull { it.id == id }
            ?: _detectedHosts.value.firstOrNull { it.id == id }

    @Synchronized
    fun upsert(config: SshHostConfig) {
        val sanitized = if (config.rememberCredentials) {
            config
        } else {
            config.copy(password = "", passphrase = "")
        }
        val list = _hosts.value.toMutableList()
        val idx = list.indexOfFirst { it.id == sanitized.id }
        if (idx >= 0) list[idx] = sanitized else list.add(0, sanitized)
        val sorted = list.sortedWith(
            compareByDescending<SshHostConfig> { it.lastConnectedAt }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName },
        )
        writeAll(sorted)
        _hosts.value = sorted
    }

    @Synchronized
    fun markConnected(id: String) {
        val existing = _hosts.value.firstOrNull { it.id == id } ?: return
        upsert(existing.copy(lastConnectedAt = System.currentTimeMillis()))
    }

    @Synchronized
    fun delete(id: String) {
        val next = _hosts.value.filterNot { it.id == id }
        if (next.size != _hosts.value.size) {
            writeAll(next)
            _hosts.value = next
        }
        if (getActiveSession()?.hostId == id) clearActiveSession()
    }

    fun setActiveSession(hostId: String, remoteRoot: String) {
        sessionPrefs.edit()
            .putString(KEY_ACTIVE_HOST_ID, hostId)
            .putString(KEY_ACTIVE_REMOTE_ROOT, remoteRoot)
            .apply()
    }

    fun getActiveSession(): ActiveSshSession? {
        val hostId = sessionPrefs.getString(KEY_ACTIVE_HOST_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val root = sessionPrefs.getString(KEY_ACTIVE_REMOTE_ROOT, null)?.takeIf { it.isNotBlank() } ?: return null
        return ActiveSshSession(hostId, root)
    }

    fun clearActiveSession() {
        sessionPrefs.edit().clear().apply()
    }

    private fun readAll(): List<SshHostConfig> =
        runCatching { readAllUnsafe() }.getOrDefault(emptyList())

    private fun readAllUnsafe(): List<SshHostConfig> {
        if (!file.exists() || file.length() == 0L) return emptyList()
        val blob = file.readBytes()
        if (blob.size <= GCM_IV_BYTES) return emptyList()
        val iv = blob.copyOfRange(0, GCM_IV_BYTES)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val plain = String(cipher.doFinal(blob.copyOfRange(GCM_IV_BYTES, blob.size)), Charsets.UTF_8)
        val arr = JSONArray(plain)
        val out = ArrayList<SshHostConfig>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += SshHostConfig(
                id = o.optString("id"),
                label = o.optString("label"),
                host = o.optString("host"),
                port = o.optInt("port", SshHostConfig.DEFAULT_PORT),
                username = o.optString("username"),
                authType = runCatching { SshAuthType.valueOf(o.optString("authType")) }
                    .getOrDefault(SshAuthType.PASSWORD),
                password = o.optString("password"),
                privateKey = o.optString("privateKey"),
                passphrase = o.optString("passphrase"),
                defaultRemotePath = o.optString("defaultRemotePath"),
                rememberCredentials = o.optBoolean("rememberCredentials", true),
                lastConnectedAt = o.optLong("lastConnectedAt", 0L),
            )
        }
        return out
    }

    private fun writeAll(hosts: List<SshHostConfig>) {
        val arr = JSONArray()
        hosts.forEach { h ->
            arr.put(
                JSONObject().apply {
                    put("id", h.id)
                    put("label", h.label)
                    put("host", h.host)
                    put("port", h.port)
                    put("username", h.username)
                    put("authType", h.authType.name)
                    put("password", h.password)
                    put("privateKey", h.privateKey)
                    put("passphrase", h.passphrase)
                    put("defaultRemotePath", h.defaultRemotePath)
                    put("rememberCredentials", h.rememberCredentials)
                    put("lastConnectedAt", h.lastConnectedAt)
                },
            )
        }
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(arr.toString().toByteArray(Charsets.UTF_8))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(iv + encrypted)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "app_ssh_host_master_key"
        private const val FILE_NAME = "ssh_hosts.enc"
        private const val SESSION_PREFS = "ssh_active_session"
        private const val KEY_ACTIVE_HOST_ID = "host_id"
        private const val KEY_ACTIVE_REMOTE_ROOT = "remote_root"
        private const val KNOWN_HOSTS_FILE = "ssh_known_hosts"
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
