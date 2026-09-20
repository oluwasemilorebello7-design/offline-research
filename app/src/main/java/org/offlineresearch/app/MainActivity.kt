package org.offlineresearch.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private val vm: ResearchViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App(vm) }
            }
        }
    }
}

@Composable
fun App(vm: ResearchViewModel) {
    val view = LocalView.current
    SideEffect { view.keepScreenOn = vm.busy || vm.importing }

    // "*/*" because .gguf / .sqlite have no registered MIME type.
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importFile(uri, true) }
    val pickKb = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importFile(uri, false) }
    val importModel = { pickModel.launch(arrayOf("*/*")) }
    val importKb = { pickKb.launch(arrayOf("*/*")) }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 16.dp)) {
        Header(vm, importModel, importKb)
        vm.importStatus?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
        when (val p = vm.phase) {
            Phase.Booting -> Centered("Loading…")
            is Phase.NeedAssets -> Setup(p, vm, importModel, importKb)
            is Phase.Failed -> Centered(p.message)
            Phase.Ready -> Chat(vm)
        }
    }
}

@Composable
private fun Header(vm: ResearchViewModel, importModel: () -> Unit, importKb: () -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Offline Research", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (vm.turns.isNotEmpty() && !vm.busy) TextButton(onClick = vm::clear) { Text("Clear") }
        }
        Text(
            if (vm.networkBlockedByOs) "🔒 No INTERNET permission – the OS blocks all network access for this app"
            else "⚠ This build requests INTERNET permission",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary,
        )
        Text(vm.kbInfo, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
        if (!vm.busy && !vm.importing) Row {
            TextButton(onClick = importModel) { Text("Import model", fontSize = 12.sp) }
            TextButton(onClick = importKb) { Text("Import knowledge base", fontSize = 12.sp) }
        }
        if (vm.models.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.models.forEach { f ->
                    FilterChip(selected = f.name == vm.activeModel, onClick = { vm.loadModel(f) }, label = { Text(f.name, fontSize = 12.sp) })
                }
            }
        } else vm.activeModel?.let { Text("Model: $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary) }
    }
}

@Composable
private fun Centered(msg: String) =
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(msg) }

@Composable
private fun Setup(p: Phase.NeedAssets, vm: ResearchViewModel, importModel: () -> Unit, importKb: () -> Unit) {
    Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Almost there – add your offline assets", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("1. A GGUF model file (downloaded once, e.g. from Hugging Face)\n2. A knowledge base file (knowledge.sqlite built with scripts/build_index.py or the Colab notebook)")
        Button(onClick = importModel, enabled = !vm.importing) { Text("Import model (.gguf)") }
        Button(onClick = importKb, enabled = !vm.importing) { Text("Import knowledge base (.sqlite)") }
        Text("Computer route: copy to ${p.dir}/models/ and .../kb/knowledge.sqlite with scripts/push_assets.sh, then reload.", fontSize = 12.sp)
        OutlinedButton(onClick = vm::refresh, enabled = !vm.importing) { Text("Reload") }
    }
}

@Composable
private fun Chat(vm: ResearchViewModel) {
    val listState = rememberLazyListState()
    val last = vm.turns.lastOrNull()
    LaunchedEffect(vm.turns.size, last?.answer?.length) {
        if (vm.turns.isNotEmpty()) listState.animateScrollToItem(vm.turns.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), state = listState) {
            if (vm.turns.isEmpty()) item { Examples(vm) }
            itemsIndexed(vm.turns) { _, t -> TurnCard(t) }
        }
        var input by remember { mutableStateOf("") }
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Ask anything…") }, maxLines = 4, enabled = !vm.busy,
            )
            Spacer(Modifier.width(8.dp))
            if (vm.busy) Button(onClick = vm::stop) { Text("Stop") }
            else Button(onClick = { vm.ask(input); input = "" }, enabled = input.isNotBlank()) { Text("Ask") }
        }
    }
}

@Composable
private fun Examples(vm: ResearchViewModel) {
    val examples = listOf(
        "Compare the Roman Republic and the Athenian democracy: how did each choose its leaders?",
        "Why did the Bronze Age collapse happen? Give competing theories.",
        "How does CRISPR differ from older gene-editing tools like TALENs?",
        "I'm hiking above 4000 m tomorrow. What is altitude sickness and how do I prevent it?",
    )
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Try one (works in airplane mode):", color = MaterialTheme.colorScheme.secondary)
        examples.forEach { ex -> OutlinedButton(onClick = { vm.ask(ex) }, modifier = Modifier.fillMaxWidth()) { Text(ex, fontSize = 13.sp) } }
    }
}

@Composable
private fun TurnCard(t: Turn) {
    var showSources by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(t.question, fontWeight = FontWeight.Bold)
        if (!t.done && t.answer.isEmpty()) Text(t.status, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
        if (t.answer.isNotEmpty()) SelectionContainer { Text(t.answer.trim()) }
        if (t.sources.isNotEmpty()) {
            TextButton(onClick = { showSources = !showSources }) { Text(if (showSources) "Hide sources" else "Sources (${t.sources.size})") }
            if (showSources) t.sources.forEachIndexed { i, h ->
                Text("[${i + 1}] ${h.title}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(h.text.take(320) + "…", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
            }
        }
        if (t.stats.isNotEmpty()) Text(t.stats, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
    }
}
