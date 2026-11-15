package com.invictus.kodex.core.external

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.io.File

/**
 * Opens a Termux session in a project folder through Termux's public RUN_COMMAND intent.
 * Nothing is bundled in the APK: Termux is a separate app that already has a real PTY and
 * every package (git, ssh, python, node, gcc, ...).
 *
 * One-time user setup (shown by the setup dialog): `allow-external-apps=true` in
 * ~/.termux/termux.properties, `termux-setup-storage`, and Termux's "Display over other apps".
 */
object TermuxLauncher {
    const val PACKAGE = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val RELEASES_URL = "https://github.com/termux/termux-app/releases"

    /** Pasted into Termux once; safe to run again. */
    const val SETUP_COMMAND =
        "mkdir -p ~/.termux && (grep -qs '^allow-external-apps=true' ~/.termux/termux.properties || " +
            "echo 'allow-external-apps=true' >> ~/.termux/termux.properties) && " +
            "termux-reload-settings && termux-setup-storage"

    private const val ACTION = "com.termux.RUN_COMMAND"
    private const val SERVICE = "com.termux.app.RunCommandService"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"

    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Starts a login shell in [dir] and brings Termux to the front. False when the call was refused. */
    fun openSession(context: Context, dir: File): Boolean = try {
        val intent = Intent(ACTION)
            .setClassName(PACKAGE, SERVICE)
            .putExtra("com.termux.RUN_COMMAND_PATH", BASH)
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-l"))
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", dir.path)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            .putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0") // new session, open Termux
        context.startService(intent) != null
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalStateException) {
        false
    }

    /** Plain launch of Termux (setup dialog), no working directory. */
    fun launch(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    fun openReleasesPage(context: Context) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
