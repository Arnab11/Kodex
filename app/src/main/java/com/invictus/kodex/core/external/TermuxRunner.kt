package com.invictus.kodex.core.external

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs one shell command in Termux (background, no UI) and returns its output when it finishes.
 * Termux sends the result back through a PendingIntent, so there is no live streaming and no
 * stdin: this is for build / test / git style commands, not interactive programs.
 */
object TermuxRunner {

    data class Result(
        val stdout: String,
        val stderr: String,
        val exitCode: Int?,
        /** Set when Termux could not run the command at all (bad path, not allowed, ...). */
        val error: String?,
    )

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Result>>()
    private val nextId = AtomicInteger(1)

    internal fun deliver(id: Int, result: Result) {
        pending.remove(id)?.complete(result)
    }

    suspend fun run(
        context: Context,
        dir: File?,
        command: String,
        timeoutMs: Long = 10 * 60_000L,
    ): Result {
        val app = context.applicationContext
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<Result>()
        pending[id] = deferred

        val resultIntent = Intent(app, TermuxResultReceiver::class.java)
            .putExtra(TermuxResultReceiver.EXTRA_ID, id)
        // Termux fills the result into this intent, so it has to be mutable (API 31+; older is mutable already).
        val flags = PendingIntent.FLAG_ONE_SHOT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val pendingIntent = PendingIntent.getBroadcast(app, id, resultIntent, flags)

        val started = TermuxLauncher.startBackground(
            context = app,
            args = arrayOf("-lc", command),
            workDir = dir,
            resultIntent = pendingIntent,
        )
        if (!started) {
            pending.remove(id)
            return Result("", "", null, "Termux refused the command (setup incomplete?)")
        }
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
                ?: Result("", "", null, "Timed out waiting for Termux (the command may still be running)")
        } finally {
            pending.remove(id)
        }
    }
}

/** Receives Termux's result bundle and hands it to [TermuxRunner]. Not exported: only our PendingIntent reaches it. */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        if (id < 0) return
        val bundle = intent.getBundleExtra("result")
        val errmsg = bundle?.getString("errmsg")
        val result = TermuxRunner.Result(
            stdout = bundle?.getString("stdout").orEmpty(),
            stderr = bundle?.getString("stderr").orEmpty(),
            exitCode = if (bundle != null && bundle.containsKey("exitCode")) bundle.getInt("exitCode") else null,
            error = errmsg?.takeIf { it.isNotBlank() },
        )
        TermuxRunner.deliver(id, result)
    }

    companion object {
        const val EXTRA_ID = "kodex_run_id"
    }
}
