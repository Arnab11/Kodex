package com.invictus.kodex.github.install

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import android.os.Process
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.invictus.kodex.R
import com.invictus.kodex.ai.notify.AppVisibility
import com.invictus.kodex.github.notify.GhNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Outcome reported by the system installer (plan 8.4). */
sealed interface InstallResult {
    data object Success : InstallResult
    data object Cancelled : InstallResult
    data class Failed(val message: String?) : InstallResult
}

/** Process-wide bridge from [InstallResultReceiver] to whoever started the install. */
object InstallEvents {
    private val _results = MutableSharedFlow<InstallResult>(extraBufferCapacity = 4)
    val results: SharedFlow<InstallResult> = _results.asSharedFlow()
    internal fun emit(r: InstallResult) { _results.tryEmit(r) }

    /** Sessions that asked Android to skip its dialog, with the APK so the install can be repeated with the dialog. */
    internal val silentSessions = ConcurrentHashMap<Int, File>()

    /** Sessions for which Android did put up its confirmation (so an abort is the person saying no). */
    internal val dialogShown: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** Set once the platform turned down a silent session (some OEM builds do): later installs ask up front. */
    @Volatile internal var silentRefused = false
}

/**
 * Installs through the PackageInstaller session API (update-in-place, status callbacks), the way Morphe Manager
 * does: [install] is only called after the user taps Install. An update of an app this app installed (Android 12+,
 * this app being its installer / update owner) is applied without the system dialog; the first install, and any
 * update Android will not take silently, shows the system confirmation as usual. Falls back to ACTION_VIEW +
 * FileProvider if a session cannot be created.
 */
class ApkInstaller(private val context: Context) {

    fun canRequestInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Settings screen where the user allows this app to install packages. */
    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        try {
            val packageName = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName
            val silent = !InstallEvents.silentRefused && packageName != null && canUpdateSilently(packageName)
            installWithSession(apk, requireUserAction = !silent)
        } catch (e: IOException) {
            installWithIntent(apk)
        } catch (e: SecurityException) {
            installWithIntent(apk)
        }
    }

    /**
     * Whether Android would update [packageName] without its dialog: Android 12+, the installed app targets
     * Android 12+, and this app is its update owner (Android 14+) or else its installer.
     */
    private fun canUpdateSilently(packageName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val pm = context.packageManager
        val installed = runCatching { pm.getPackageInfo(packageName, 0) }.getOrNull() ?: return false
        if ((installed.applicationInfo?.targetSdkVersion ?: 0) < Build.VERSION_CODES.S) return false
        val source = runCatching { pm.getInstallSourceInfo(packageName) }.getOrNull() ?: return false
        val owner = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) source.updateOwnerPackageName else null
        // An update owner, once claimed, outranks whoever installed the app.
        return owner?.let { it == context.packageName } ?: (source.installingPackageName == context.packageName)
    }

    @Suppress("DEPRECATION")
    private fun installWithSession(apk: File, requireUserAction: Boolean) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(apk.length())
        // A user-initiated install of a known package.
        params.setInstallReason(PackageManager.INSTALL_REASON_USER)
        params.setOriginatingUid(Process.myUid())
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName?.let { params.setAppPackageName(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Claim the updates of what this app installs, so they can stay dialog-free.
            params.setRequestUpdateOwnership(true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(
                if (requireUserAction) {
                    PackageInstaller.SessionParams.USER_ACTION_REQUIRED
                } else {
                    PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                },
            )
        }
        val id = installer.createSession(params)
        if (!requireUserAction) InstallEvents.silentSessions[id] = apk
        var session: PackageInstaller.Session? = null
        try {
            val s = installer.openSession(id)
            session = s
            apk.inputStream().use { input ->
                s.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    s.fsync(out)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
            val pi = PendingIntent.getBroadcast(
                context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            s.commit(pi.intentSender)
        } catch (e: Exception) {
            InstallEvents.silentSessions.remove(id)
            runCatching { session?.abandon() }
            if (session == null) runCatching { installer.abandonSession(id) }
            throw if (e is IOException || e is SecurityException) e else IOException(e)
        } finally {
            session?.close()
        }
    }

    private fun installWithIntent(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.ghfiles", apk)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(view)
    }
}

/** Receives the PackageInstaller status; launches the system confirmation when asked to. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            InstallEvents.dialogShown.add(sessionId)
        } else {
            val silentApk = InstallEvents.silentSessions.remove(sessionId)
            val asked = InstallEvents.dialogShown.remove(sessionId)
            if (status == PackageInstaller.STATUS_FAILURE_ABORTED && silentApk != null && !asked) {
                // Android turned the silent update down without asking anyone: do it again with its dialog.
                InstallEvents.silentRefused = true
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        ApkInstaller(context.applicationContext).install(silentApk)
                    } catch (e: Exception) {
                        InstallEvents.emit(InstallResult.Failed(e.message))
                    } finally {
                        pending.finish()
                    }
                }
                return
            }
        }
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (AppVisibility.visible) {
                        // App is on screen: show the installer right now, in the foreground.
                        runCatching { context.startActivity(it) }
                    } else {
                        // App is in the background: never start the installer behind the user's back.
                        // A tap on this notification opens it in the foreground instead.
                        GhNotifier(context).installReady(it)
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                GhNotifier(context).clearInstallReady()
                // Remember which commit now runs, before anyone reacts to the event.
                InstalledBuilds(context).commit(context, intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME))
                InstallEvents.emit(InstallResult.Success)
                GhNotifier(context).result(
                    GhNotifier.KIND_INSTALL, context.getString(R.string.gh_notif_install_ok_title),
                    context.getString(R.string.gh_notif_install_ok_text),
                )
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                GhNotifier(context).clearInstallReady()
                InstallEvents.emit(InstallResult.Cancelled)
            }
            PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                // Usually Google Play Protect (or the device policy) refused the APK itself, not our installer.
                GhNotifier(context).clearInstallReady()
                val message = context.getString(R.string.gh_install_blocked_play_protect)
                InstallEvents.emit(InstallResult.Failed(message))
                GhNotifier(context).result(
                    GhNotifier.KIND_INSTALL, context.getString(R.string.gh_notif_install_failed_title), message, ok = false,
                )
            }
            else -> {
                GhNotifier(context).clearInstallReady()
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                InstallEvents.emit(InstallResult.Failed(message))
                GhNotifier(context).result(
                    GhNotifier.KIND_INSTALL, context.getString(R.string.gh_notif_install_failed_title),
                    message ?: context.getString(R.string.gh_notif_install_failed_title), ok = false,
                )
            }
        }
    }
}
