package org.offlineresearch.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

sealed interface Phase {
    data object Booting : Phase
    data class NeedAssets(val dir: String) : Phase
    data class Failed(val message: String) : Phase
    data object Ready : Phase
}

data class Turn(
    val question: String,
    val answer: String = "",
    val sources: List<Hit> = emptyList(),
    val status: String = "",
    val stats: String = "",
    val done: Boolean = false,
)

class ResearchViewModel(app: Application) : AndroidViewModel(app) {

    var phase by mutableStateOf<Phase>(Phase.Booting); private set
    var models by mutableStateOf<List<File>>(emptyList()); private set
    var activeModel by mutableStateOf<String?>(null); private set
    var kbInfo by mutableStateOf("No knowledge base loaded"); private set
    var turns by mutableStateOf<List<Turn>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    /** Progress / result text for file imports (null = nothing to show). */
    var importStatus by mutableStateOf<String?>(null); private set
    var importing by mutableStateOf(false); private set

    /** True when the APK does not even request the INTERNET permission, i.e. the OS blocks all sockets. */
    val networkBlockedByOs: Boolean = run {
        val pm = app.packageManager
        val info = pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        info.requestedPermissions?.contains(Manifest.permission.INTERNET) != true
    }

    private val engine = LlamaEngine()
    private var kb: KnowledgeBase? = null
    private var job: Job? = null

    private val root: File get() = getApplication<Application>().getExternalFilesDir(null)!!

    init { refresh() }

    fun refresh() {
        phase = Phase.Booting
        viewModelScope.launch(Dispatchers.Default) {
            val modelDir = File(root, AppConfig.MODELS_DIR).apply { mkdirs() }
            File(root, AppConfig.KB_DIR).mkdirs()
            val found = modelDir.listFiles { f -> f.extension.equals("gguf", true) }?.sortedBy { it.length() }.orEmpty()
            models = found

            kb?.close(); kb = null
            val kbFile = File(root, "${AppConfig.KB_DIR}/${AppConfig.KB_FILE}")
            if (kbFile.exists()) {
                runCatching { KnowledgeBase(kbFile) }
                    .onSuccess { k ->
                        kb = k
                        val m = runCatching { k.meta() }.getOrDefault(emptyMap())
                        kbInfo = String.format(
                            Locale.US, "KB: %s · %s chunks · %.1f GB",
                            m["name"] ?: "knowledge", m["chunks"] ?: "?", kbFile.length() / 1e9,
                        )
                    }
                    .onFailure { kbInfo = "KB failed to open: ${it.message}" }
            } else {
                kbInfo = "No knowledge base (model-only mode)"
            }

            if (found.isEmpty()) {
                phase = Phase.NeedAssets(root.absolutePath)
            } else {
                loadModelBlocking(found.first())
            }
        }
    }


    /**
     * Copies a model (.gguf) or knowledge base (.sqlite) picked with the system file picker into the app's
     * private folder, so no computer / adb is needed. Uses a .part temp file so a failed copy never leaves a broken model.
     */
    fun importFile(uri: Uri, isModel: Boolean) {
        if (busy || importing) return
        importing = true
        importStatus = "Starting import…"
        viewModelScope.launch(Dispatchers.IO) {
            var tmp: File? = null
            try {
                val cr = getApplication<Application>().contentResolver
                var name = "import"
                var size = -1L
                cr.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (ni >= 0) name = c.getString(ni) ?: name
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
                if (isModel && !name.endsWith(".gguf", ignoreCase = true)) {
                    importStatus = "That is not a .gguf model file ($name)."; return@launch
                }
                if (size > 0 && root.usableSpace < size + 300_000_000L) {
                    importStatus = "Not enough free storage for $name (needs ${size / 1_000_000} MB)."; return@launch
                }
                val dest = if (isModel) File(root, "${AppConfig.MODELS_DIR}/${name.replace('/', '_')}")
                           else File(root, "${AppConfig.KB_DIR}/${AppConfig.KB_FILE}")
                dest.parentFile?.mkdirs()
                val part = File(dest.parentFile, dest.name + ".part")
                tmp = part

                val input = cr.openInputStream(uri) ?: run { importStatus = "Could not open the selected file."; return@launch }
                input.use { inp ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20)
                        var copied = 0L
                        var lastReport = 0L
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            copied += n
                            if (copied - lastReport >= 32_000_000L) {
                                lastReport = copied
                                importStatus = "Importing $name: ${copied / 1_000_000} / ${if (size > 0) "${size / 1_000_000}" else "?"} MB"
                            }
                        }
                    }
                }
                if (!isModel) {
                    val header = ByteArray(16)
                    part.inputStream().use { it.read(header) }
                    if (String(header, Charsets.ISO_8859_1) != "SQLite format 3\u0000") {
                        part.delete(); importStatus = "That file is not a SQLite database."; return@launch
                    }
                    kb?.close(); kb = null
                }
                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) { importStatus = "Could not save $name."; return@launch }
                importStatus = "Imported $name. You can delete the original download to free space."
                refresh()
            } catch (e: Exception) {
                tmp?.delete()
                importStatus = "Import failed: ${e.message}"
            } finally {
                importing = false
            }
        }
    }

    fun loadModel(f: File) {
        if (busy) return
        phase = Phase.Booting
        viewModelScope.launch(Dispatchers.Default) { loadModelBlocking(f) }
    }

    private fun loadModelBlocking(f: File) {
        val ok = runCatching { engine.load(f) }.getOrDefault(false)
        if (ok) { activeModel = f.name; phase = Phase.Ready }
        else phase = Phase.Failed("Could not load ${f.name}. Check the file is a complete GGUF and that this llama.cpp build supports its architecture.")
    }

    fun ask(question: String) {
        val q = question.trim()
        if (busy || q.isEmpty() || phase != Phase.Ready) return
        turns = turns + Turn(q)
        busy = true
        val agent = ResearchAgent(engine, kb)
        job = viewModelScope.launch {
            try {
                agent.ask(q).collect { ev ->
                    when (ev) {
                        is AgentEvent.Status -> updateLast { it.copy(status = ev.text) }
                        is AgentEvent.Sources -> updateLast { it.copy(sources = ev.hits) }
                        is AgentEvent.Token -> updateLast { it.copy(answer = it.answer + ev.text) }
                        is AgentEvent.Done -> updateLast {
                            val s = ev.stats
                            val txt = if (s == null) "" else String.format(
                                Locale.US, "%d prompt tok @ %.0f tok/s · %d gen tok @ %.1f tok/s · %.1fs total",
                                s.promptTokens, s.prefillTps, s.generatedTokens, s.decodeTps, ev.totalMs / 1000.0,
                            )
                            it.copy(done = true, stats = txt)
                        }
                    }
                }
            } finally {
                updateLast { it.copy(done = true) }
                busy = false
            }
        }
    }

    fun stop() { engine.cancel(); job?.cancel() }

    fun clear() { if (!busy) turns = emptyList() }

    private fun updateLast(f: (Turn) -> Turn) {
        if (turns.isEmpty()) return
        turns = turns.dropLast(1) + f(turns.last())
    }

    override fun onCleared() {
        engine.cancel()
        engine.close()
        kb?.close()
    }
}
