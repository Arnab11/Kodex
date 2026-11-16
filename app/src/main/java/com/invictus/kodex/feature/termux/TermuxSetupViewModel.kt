package com.invictus.kodex.feature.termux

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.invictus.kodex.core.external.SetupSelection
import com.invictus.kodex.core.external.TermuxLauncher
import com.invictus.kodex.core.external.TermuxPreset
import com.invictus.kodex.core.external.TermuxProbe
import com.invictus.kodex.core.external.TermuxRunner
import com.invictus.kodex.core.external.TermuxSetupModel
import com.invictus.kodex.core.external.TermuxSetupPrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** State of the Termux setup screen: live checks, the user's choices (persisted) and the install result. */
class TermuxSetupViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Application get() = getApplication()

    var termuxInstalled by mutableStateOf(false); private set
    var permissionGranted by mutableStateOf(false); private set
    var probe by mutableStateOf<TermuxProbe?>(null); private set
    var checking by mutableStateOf(false); private set
    var overlayAck by mutableStateOf(TermuxSetupPrefs.overlayAcknowledged(ctx)); private set
    var selection by mutableStateOf(TermuxSetupPrefs.load(ctx)); private set
    var installStarted by mutableStateOf(false); private set

    /** After an install: each wanted package and whether Termux now reports it installed. */
    var verify by mutableStateOf<List<Pair<String, Boolean>>?>(null); private set

    private var job: Job? = null

    fun update(sel: SetupSelection) {
        selection = sel
        TermuxSetupPrefs.save(ctx, sel)
    }

    fun applyPreset(preset: TermuxPreset) = update(selection.copy(packages = preset.packages))

    fun togglePackage(name: String) {
        val next = if (name in selection.packages) selection.packages - name else selection.packages + name
        update(selection.copy(packages = next))
    }

    fun acknowledgeOverlay() {
        overlayAck = true
        TermuxSetupPrefs.setOverlayAcknowledged(ctx, true)
    }

    /** The user is about to paste the setup command in Termux: the next resume may probe once. */
    private var armed = false

    fun arm() {
        armed = true
    }

    /**
     * Re-reads everything. Asking Termux (the probe) is the only way to learn whether it accepts
     * Kodex, but a Termux that has not enabled external apps shows an error notification for every try. So the probe runs
     * automatically only when it is known to work, after the user pasted the command, or after an
     * install; otherwise only on an explicit tap ([userInitiated]).
     */
    fun refresh(userInitiated: Boolean = false) {
        termuxInstalled = TermuxLauncher.isInstalled(ctx)
        permissionGranted = termuxInstalled && TermuxLauncher.hasPermission(ctx)
        if (!permissionGranted) {
            probe = null
            return
        }
        if (!(userInitiated || armed || installStarted || TermuxSetupPrefs.externalOk(ctx))) {
            probe = null
            return
        }
        armed = false
        job?.cancel()
        job = viewModelScope.launch {
            checking = true
            val r = TermuxRunner.run(ctx, null, TermuxSetupModel.PROBE_COMMAND, timeoutMs = 8_000L)
            val parsed = if (r.error == null && r.stdout.isNotBlank()) {
                TermuxSetupModel.parseProbe(r.stdout)
            } else {
                // No answer: Termux is not accepting external apps yet (or could not start).
                TermuxProbe(externalAllowed = false, storageOk = false, mirrorHost = null, installed = emptySet())
            }
            probe = parsed
            TermuxSetupPrefs.setExternalOk(ctx, parsed.externalAllowed)
            if (installStarted && parsed.externalAllowed) {
                verify = TermuxSetupModel.allPackages(selection).map { it to (it in parsed.installed) }
            }
            checking = false
        }
    }

    /** Opens a visible Termux session that runs the chosen setup. False when Termux refused the call. */
    fun startInstall(): Boolean {
        val ok = TermuxLauncher.startForeground(ctx, TermuxSetupModel.buildScript(selection))
        installStarted = ok
        verify = null
        return ok
    }

    /** Setup finished: switch the terminal features on. */
    fun finish() = TermuxSetupPrefs.setEnabled(ctx, true)
}
