package com.invictus.kodex.feature.termux

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.invictus.kodex.core.external.TermuxLauncher

/**
 * Returns a function that runs an action only once Termux is usable. Nothing is forced on the
 * user: when Termux is missing or the permission is denied, [onOpenSetup] is called (callers show
 * a "Setup" snackbar, not a screen), and the one-time "run commands" permission is requested on the way.
 */
@Composable
fun rememberTermuxGate(onOpenSetup: () -> Unit): (action: () -> Unit) -> Unit {
    val context = LocalContext.current
    val openSetup by rememberUpdatedState(onOpenSetup)
    var after by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = after
        after = null
        if (granted) action?.invoke() else openSetup()
    }
    return { action ->
        when {
            !TermuxLauncher.isInstalled(context) -> openSetup()
            !TermuxLauncher.hasPermission(context) -> {
                after = action
                permission.launch(TermuxLauncher.PERMISSION)
            }
            else -> action()
        }
    }
}
