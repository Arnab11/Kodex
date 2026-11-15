package com.invictus.kodex.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.invictus.kodex.github.install.ApkInstaller
import com.invictus.kodex.github.install.InstallEvents
import com.invictus.kodex.github.install.InstallResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the About screen and the update sheet show. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val done: Long, val total: Long) : UpdateState
    data class Ready(val info: UpdateInfo, val file: File) : UpdateState
    data class Error(val message: String, val info: UpdateInfo? = null) : UpdateState
}

/**
 * In-app updater, same flow as xmd's: check GitHub releases (Stable or Preview channel), show a sheet with the notes,
 * download the APK for this device (resumable, checksum-verified) and hand it to [ApkInstaller]. Checking on its own
 * at launch is a setting and is OFF by default.
 */
object UpdateController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences("kodex_update", Context.MODE_PRIVATE) }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** The sheet is shown for a manual check that found something, or an automatic one (unless that version was skipped). */
    private val _sheetVisible = MutableStateFlow(false)
    val sheetVisible: StateFlow<Boolean> = _sheetVisible.asStateFlow()

    private val _autoCheck = MutableStateFlow(false)
    val autoCheck: StateFlow<Boolean> = _autoCheck.asStateFlow()

    /** A hint shown in the sheet (e.g. "allow installs first"), cleared by the next action. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _channel = MutableStateFlow(UpdateChannel.STABLE)
    val channel: StateFlow<UpdateChannel> = _channel.asStateFlow()

    private var job: Job? = null
    private var launchChecked = false

    fun init(context: Context) {
        app = context.applicationContext
        _autoCheck.value = prefs.getBoolean(KEY_AUTO, false)
        _channel.value = runCatching { UpdateChannel.valueOf(prefs.getString(KEY_CHANNEL, null).orEmpty()) }
            .getOrDefault(UpdateChannel.STABLE)
        // A failed install (or the person backing out of the system dialog) puts the update back to "ready".
        scope.launch {
            InstallEvents.results.collect { r ->
                val s = _state.value
                if (s is UpdateState.Ready) {
                    when (r) {
                        is InstallResult.Failed -> _state.value = UpdateState.Error(r.message ?: "Install failed", s.info)
                        else -> Unit
                    }
                }
            }
        }
    }

    fun setAutoCheck(on: Boolean) {
        _autoCheck.value = on
        prefs.edit().putBoolean(KEY_AUTO, on).apply()
    }

    fun setChannel(channel: UpdateChannel) {
        if (_channel.value == channel) return
        _channel.value = channel
        prefs.edit().putString(KEY_CHANNEL, channel.name).apply()
        // What counts as "newer" depends on the channel: forget the old answer.
        if (_state.value !is UpdateState.Downloading && _state.value !is UpdateState.Checking) _state.value = UpdateState.Idle
    }

    /** Once per app launch, only when the setting is on. */
    fun autoCheckOnLaunch() {
        if (launchChecked || !_autoCheck.value) return
        launchChecked = true
        check(manual = false)
    }

    fun check(manual: Boolean) {
        val s = _state.value
        if (s is UpdateState.Checking || s is UpdateState.Downloading) {
            if (manual && s is UpdateState.Downloading) _sheetVisible.value = true
            return
        }
        _state.value = UpdateState.Checking
        job = scope.launch {
            val installed = installedVersionName()
            val result = withContext(Dispatchers.IO) { UpdateChecker.check(_channel.value, installed) }
            when (result) {
                is CheckResult.UpToDate -> _state.value = UpdateState.UpToDate
                is CheckResult.Failed -> _state.value = if (manual) UpdateState.Error(result.message) else UpdateState.Idle
                is CheckResult.Available -> {
                    val info = result.info
                    _state.value = UpdateState.Available(info)
                    if (manual || prefs.getString(KEY_SKIPPED, null) != info.tag) _sheetVisible.value = true
                }
            }
        }
    }

    fun download() {
        val info = when (val s = _state.value) {
            is UpdateState.Available -> s.info
            is UpdateState.Error -> s.info ?: return
            else -> return
        }
        _notice.value = null
        _state.value = UpdateState.Downloading(info, 0L, info.asset.size)
        job = scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    UpdateDownloader.download(app, info) { done, total ->
                        _state.update { cur -> if (cur is UpdateState.Downloading) cur.copy(done = done, total = total) else cur }
                    }
                }
                _state.value = UpdateState.Ready(info, file)
            } catch (e: Exception) {
                _state.value = UpdateState.Error(e.message ?: "Download failed", info)
            }
        }
    }

    /** Hands the downloaded APK to the installer. The system asks to confirm the first time; later updates can go through silently. */
    fun install() {
        val s = _state.value as? UpdateState.Ready ?: return
        if (!app.packageManager.canRequestPackageInstalls()) {
            runCatching {
                app.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            _notice.value = "Allow Kodex to install apps in the screen that just opened, then tap Install again."
            return
        }
        _notice.value = null
        scope.launch {
            withContext(Dispatchers.IO) { ApkInstaller(app).install(s.file) }
        }
    }

    /** "Not now" on this version: the launch check will not open the sheet for it again (a manual check still shows it). */
    fun skipThisVersion() {
        val info = when (val s = _state.value) {
            is UpdateState.Available -> s.info
            is UpdateState.Ready -> s.info
            is UpdateState.Error -> s.info
            else -> null
        }
        if (info != null) prefs.edit().putString(KEY_SKIPPED, info.tag).apply()
        _sheetVisible.value = false
    }

    fun dismissSheet() {
        _sheetVisible.value = false
    }

    fun showSheet() {
        if (_state.value !is UpdateState.Idle && _state.value !is UpdateState.UpToDate) _sheetVisible.value = true
    }

    fun cancelDownload() {
        val s = _state.value as? UpdateState.Downloading ?: return
        job?.cancel()
        _state.value = UpdateState.Available(s.info)
    }

    private fun installedVersionName(): String =
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()

    private const val KEY_AUTO = "auto_check"
    private const val KEY_CHANNEL = "channel"
    private const val KEY_SKIPPED = "skipped_tag"
}
