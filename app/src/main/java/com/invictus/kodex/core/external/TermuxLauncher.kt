package com.invictus.kodex.core.external

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Base64
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

    private const val ACTION = "com.termux.RUN_COMMAND"
    private const val SERVICE = "com.termux.app.RunCommandService"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"

    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Starts a login shell in [dir] and brings Termux to the front. False when the call was refused. */
    fun openSession(context: Context, dir: File): Boolean {
        // A background call to a Termux that blocks external apps would add a second error notification.
        if (TermuxSetupPrefs.externalOk(context)) installCli(context)
        return try {
            val intent = baseIntent()
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
    }

    /** Runs bash with [args] in the background in [dir]; Termux reports back through [resultIntent]. */
    fun startBackground(context: Context, args: Array<String>, workDir: File?, resultIntent: PendingIntent?): Boolean =
        try {
            val intent = baseIntent()
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args)
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            if (workDir != null) intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", workDir.path)
            if (resultIntent != null) intent.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", resultIntent)
            context.startService(intent) != null
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalStateException) {
            false
        }

    /** Opens a visible Termux session running [script] (bash -lc), so the user watches it. */
    fun startForeground(context: Context, script: String, workDir: File? = null): Boolean = try {
        val intent = baseIntent()
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", script))
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            .putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        if (workDir != null) intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", workDir.path)
        context.startService(intent) != null
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalStateException) {
        false
    }

    /** Runs [file] in a visible Termux session (cwd = its folder). False if it is not runnable or Termux refused. */
    fun runFile(context: Context, file: File): Boolean {
        val script = TermuxRunFile.script(file) ?: return false
        return startForeground(context, script, file.parentFile)
    }

    /**
     * Runs [command] (may be several lines) in a visible Termux session in [dir], so programs that need
     * the keyboard work. Shows the exit code and waits for Enter, like Run file.
     */
    fun runCommandVisible(context: Context, dir: File, command: String): Boolean {
        fun sq(s: String) = "'" + s.replace("'", "'\\''") + "'"
        val script = buildString {
            appendLine("printf '%s\\n' ${sq("$ " + command.trim())}")
            appendLine(command.trim())
            appendLine("code=\$?")
            appendLine("echo; echo \"[exit \$code] Press Enter to close\"; read -r _")
        }
        return startForeground(context, script, dir)
    }

    /** Android's "Display over other apps" page for Termux (Termux needs it to open its session). */
    fun openOverlaySettings(context: Context) {
        val intent = Intent(
            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$PACKAGE"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun baseIntent(): Intent =
        Intent(ACTION).setClassName(PACKAGE, SERVICE).putExtra("com.termux.RUN_COMMAND_PATH", BASH)

    /**
     * Keeps a `kodex` command in Termux up to date: `kodex file.py` opens a file in the editor,
     * `kodex .` / `kodex folder` opens that folder as the project. Cheap and idempotent, so it
     * runs every time a session is opened.
     */
    private fun installCli(context: Context) {
        val script = cliScript(context.packageName)
        val b64 = Base64.encodeToString(script.toByteArray(), Base64.NO_WRAP)
        val target = "/data/data/com.termux/files/usr/bin/kodex"
        val cmd = "echo $b64 | base64 -d > $target && chmod 700 $target"
        startBackground(context, arrayOf("-c", cmd), workDir = null, resultIntent = null)
    }

    private fun cliScript(appId: String): String {
        val component = "$appId/com.invictus.kodex.MainActivity"
        return """#!/data/data/com.termux/files/usr/bin/sh
# kodex <file|folder> ...   open in Kodex (installed by Kodex)
[ ${'$'}# -eq 0 ] && set -- .
for p in "${'$'}@"; do
  a=${'$'}(realpath "${'$'}p" 2>/dev/null) || { echo "kodex: not found: ${'$'}p" >&2; continue; }
  if [ -d "${'$'}a" ]; then
    am start -n $component -a ${ExternalProjectOpen.ACTION} --es ${ExternalProjectOpen.EXTRA_PATH} "${'$'}a" >/dev/null
  else
    u="file://${'$'}(printf %s "${'$'}a" | sed 's/ /%20/g')"
    am start -n $component -a android.intent.action.VIEW -d "${'$'}u" >/dev/null
  fi
done
"""
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
