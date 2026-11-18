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
import com.invictus.kodex.core.iconmaker.GlyphKind
import com.invictus.kodex.core.iconmaker.IconExporter
import com.invictus.kodex.core.iconmaker.IconRenderer
import com.invictus.kodex.core.iconmaker.IconShape
import com.invictus.kodex.core.iconmaker.IconSpec
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

/**
 * Icon maker: edits an [IconSpec], renders the three adaptive layers for the previews (off the main thread,
 * a newer edit cancels the one in flight) and exports / applies them with [IconExporter].
 */
class IconMakerViewModel(
    private val app: Application,
    private val projects: ProjectRepository,
) : ViewModel() {

    class Layers(val bg: ImageBitmap, val fg: ImageBitmap, val mono: ImageBitmap)

    class Target(val root: File, val name: String)

    data class UiState(
        val spec: IconSpec = IconSpec(),
        val layers: Layers? = null,
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

    private val specFlow = MutableStateFlow(IconSpec())

    init {
        viewModelScope.launch {
            val symbols = SymbolLibrary.load(app)
            _ui.update { it.copy(symbols = symbols, symbolsLoading = false) }
            // Glyph paths are available now: draw again.
            specFlow.value = specFlow.value.copy()
        }
        viewModelScope.launch {
            specFlow.collectLatest { spec ->
                val layers = withContext(Dispatchers.Default) { render(spec) }
                _ui.update { it.copy(layers = layers) }
            }
        }
    }

    fun edit(block: IconSpec.() -> IconSpec) {
        val next = _ui.value.spec.block()
        _ui.update { it.copy(spec = next) }
        specFlow.value = next
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
            val spec = _ui.value.spec
            val result = runCatching {
                val main = AppIconApplier.findMain(target.root) ?: error("No Android app module")
                IconExporter.write(main, spec, target.root)
            }
            _effects.send(
                result.fold(
                    onSuccess = { app.getString(R.string.im_applied, it.size, target.name) },
                    onFailure = { app.getString(R.string.im_apply_failed, it.message ?: it.javaClass.simpleName) },
                ),
            )
        }
    }

    /** res/ + Play Store icon as a ZIP in Download/Kodex, ready to copy over a project's src/main. */
    fun saveZip() {
        runBusy {
            val spec = _ui.value.spec
            val name = "Kodex-icon-${System.currentTimeMillis() / 1000}.zip"
            val dir = File(app.cacheDir, "iconmaker/${System.currentTimeMillis()}")
            val zip = File(app.cacheDir, "iconmaker/$name")
            val ok = runCatching {
                IconExporter.write(dir, spec)
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

    private fun render(spec: IconSpec): Layers {
        val bg = IconRenderer.background(spec)
        val fg = IconRenderer.foreground(spec)
        val mono = IconRenderer.foreground(spec, mono = true)
        return Layers(bg.asImageBitmap(), fg.asImageBitmap(), mono.asImageBitmap())
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
