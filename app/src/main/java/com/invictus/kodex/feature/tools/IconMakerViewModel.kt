package com.invictus.kodex.feature.tools

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.invictus.kodex.KodexApp
import com.invictus.kodex.R
import com.invictus.kodex.ai.agent.AppIconApplier
import com.invictus.kodex.core.iconmaker.BgKind
import com.invictus.kodex.core.iconmaker.GlyphKind
import com.invictus.kodex.core.iconmaker.IconExporter
import com.invictus.kodex.core.iconmaker.IconRenderer
import com.invictus.kodex.core.iconmaker.IconSpec
import com.invictus.kodex.core.iconmaker.NotifSpec
import com.invictus.kodex.core.iconmaker.SymbolLibrary
import com.invictus.kodex.core.project.ProjectRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MakerMode { App, Notification }

/**
 * Icon maker: edits an [IconSpec] (launcher icon) and a [NotifSpec] (status-bar icon) that share one glyph,
 * renders what the previews need off the main thread (a newer edit cancels the one in flight) and exports /
 * applies the result with [IconExporter].
 */
class IconMakerViewModel(
    private val app: Application,
    private val projects: ProjectRepository,
) : ViewModel() {

    class Layers(val bg: ImageBitmap, val fg: ImageBitmap, val mono: ImageBitmap)

    class Target(val root: File, val name: String)

    data class UiState(
        val mode: MakerMode = MakerMode.App,
        val spec: IconSpec = IconSpec(),
        val notif: NotifSpec = NotifSpec(),
        val layers: Layers? = null,
        /** White-on-transparent notification glyph (96px). */
        val notifGlyph: ImageBitmap? = null,
        val symbols: List<SymbolLibrary.Symbol> = emptyList(),
        val symbolsLoading: Boolean = true,
        val hasPicture: Boolean = false,
        val busy: Boolean = false,
        /** Non-null while the "apply to which project" dialog is up. */
        val targets: List<Target>? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _effects = Channel<String>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    /** [rev] changes when glyph data finishes loading, so an unchanged spec still gets redrawn. */
    private data class Inputs(val spec: IconSpec, val notif: NotifSpec, val rev: Int = 0)

    private val inputs = MutableStateFlow(Inputs(IconSpec(), NotifSpec()))

    init {
        viewModelScope.launch {
            val symbols = SymbolLibrary.load(app)
            _ui.update { it.copy(symbols = symbols, symbolsLoading = false) }
            redraw()
        }
        viewModelScope.launch {
            inputs.collectLatest { i ->
                val (layers, glyph) = withContext(Dispatchers.Default) { render(i) }
                _ui.update { it.copy(layers = layers, notifGlyph = glyph) }
            }
        }
    }

    private fun redraw() = inputs.update { it.copy(rev = it.rev + 1) }

    fun setMode(mode: MakerMode) = _ui.update { it.copy(mode = mode) }

    fun edit(block: IconSpec.() -> IconSpec) {
        val next = _ui.value.spec.block()
        _ui.update { it.copy(spec = next) }
        inputs.update { it.copy(spec = next) }
    }

    fun editNotif(block: NotifSpec.() -> NotifSpec) {
        val next = _ui.value.notif.block()
        _ui.update { it.copy(notif = next) }
        inputs.update { it.copy(notif = next) }
    }

    /** One-tap look: icon color plus a solid or gradient background. */
    fun applyStyle(fg: Int, bg1: Int, bg2: Int?) {
        edit {
            copy(
                fgColor = fg,
                bgKind = if (bg2 != null) BgKind.Gradient else BgKind.Solid,
                bg1 = bg1,
                bg2 = bg2 ?: this.bg2,
                bgAngle = 45,
            )
        }
    }

    fun setPicture(uri: Uri) {
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) { decode(uri) }
            if (bmp == null) {
                _effects.send(app.getString(R.string.im_image_failed))
                return@launch
            }
            _ui.update { it.copy(hasPicture = true) }
            edit { copy(kind = GlyphKind.Picture, picture = bmp) }
        }
    }

    fun dismissTargets() = _ui.update { it.copy(targets = null) }

    /** Shows the project picker: recent projects that are Android apps. */
    fun requestApply() {
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                projects.recents.first()
                    .map { it.file }
                    .filter { it.isDirectory && AppIconApplier.findMain(it) != null }
                    .take(12)
                    .map { Target(it, it.name) }
            }
            if (found.isEmpty()) _effects.send(app.getString(R.string.im_no_projects))
            else _ui.update { it.copy(targets = found) }
        }
    }

    fun applyTo(target: Target) {
        dismissTargets()
        runBusy {
            val s = _ui.value
            val result = runCatching {
                val main = AppIconApplier.findMain(target.root) ?: error("No Android app module")
                if (s.mode == MakerMode.App) IconExporter.write(main, s.spec, target.root)
                else IconExporter.writeNotification(main, s.spec, s.notif, target.root)
            }
            _effects.send(
                result.fold(
                    onSuccess = {
                        if (s.mode == MakerMode.App) app.getString(R.string.im_applied, it.size, target.name)
                        else app.getString(R.string.im_notif_applied, it.size, target.name, IconExporter.resourceName(s.notif.name))
                    },
                    onFailure = { app.getString(R.string.im_apply_failed, it.message ?: it.javaClass.simpleName) },
                ),
            )
        }
    }

    /** res/ (+ Play Store icon) as a ZIP in Download/Kodex, ready to copy over a project's src/main. */
    fun saveZip() {
        runBusy {
            val s = _ui.value
            val stamp = System.currentTimeMillis()
            val name = (if (s.mode == MakerMode.App) "Kodex-icon-" else "Kodex-notification-icon-") + "${stamp / 1000}.zip"
            val dir = File(app.cacheDir, "iconmaker/$stamp")
            val zip = File(app.cacheDir, "iconmaker/$name")
            val ok = runCatching {
                if (s.mode == MakerMode.App) IconExporter.write(dir, s.spec)
                else IconExporter.writeNotification(dir, s.spec, s.notif)
                IconExporter.zip(dir, zip)
                AppIconApplier.saveToDevice(app, zip, name, "application/zip")
            }.getOrDefault(false)
            dir.deleteRecursively()
            zip.delete()
            _effects.send(if (ok) app.getString(R.string.im_zip_saved, name) else app.getString(R.string.im_zip_failed))
        }
    }

    private fun runBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            try {
                withContext(Dispatchers.IO) { block() }
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    private fun render(i: Inputs): Pair<Layers, ImageBitmap> {
        val bg = IconRenderer.background(i.spec)
        val fg = IconRenderer.foreground(i.spec)
        val mono = IconRenderer.foreground(i.spec, mono = true)
        val glyph = IconRenderer.notification(i.spec, i.notif, 96)
        return Layers(bg.asImageBitmap(), fg.asImageBitmap(), mono.asImageBitmap()) to glyph.asImageBitmap()
    }

    private fun decode(uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KodexApp
                IconMakerViewModel(app, app.container.projectRepository)
            }
        }
    }
}
