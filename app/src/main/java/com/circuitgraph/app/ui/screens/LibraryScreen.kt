package com.circuitgraph.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.circuitgraph.app.R
import com.circuitgraph.app.ui.CircuitUiState
import com.circuitgraph.app.ui.CircuitViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    uiState: CircuitUiState,
    viewModel: CircuitViewModel,
    onNavigateToGraph: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    var showPasteDialog by remember { mutableStateOf(false) }
    var showNewCircuitDialog by remember { mutableStateOf(false) }

    // Parametric builder states
    var adderWidth by remember { mutableFloatStateOf(3f) }
    var adderCarryIn by remember { mutableStateOf(false) }
    var adderCarryOut by remember { mutableStateOf(true) }
    var muxWidth by remember { mutableFloatStateOf(4f) }
    var popWidth by remember { mutableFloatStateOf(4f) }

    val openDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (text != null) {
                    val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "imported"
                    val hint = if (fileName.endsWith(".bench", true)) "bench" else "verilog"
                    viewModel.importNetlistText(text, name = fileName.substringBeforeLast('.'), formatHint = hint)
                    onNavigateToGraph()
                }
            } catch (_: Exception) {
            }
        }
    }

    val categories = remember(uiState.bundledNetlists) {
        listOf("All") + uiState.bundledNetlists.map { it.category }.distinct()
    }

    val filteredBundled = remember(uiState.bundledNetlists, searchQuery, selectedCategory) {
        uiState.bundledNetlists.filter { item ->
            (selectedCategory == "All" || item.category == selectedCategory) &&
                (searchQuery.isBlank() || item.displayName.contains(searchQuery, ignoreCase = true))
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Banner
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.height(148.dp).fillMaxWidth()) {
                    Image(
                        painter = painterResource(id = R.drawable.img_circuit_hero),
                        contentDescription = "CircuitGraph Hardware Benchmark Library",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.25f),
                                        Color(0xFF0B111A).copy(alpha = 0.92f)
                                    )
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Circuit Library & Logic Builders",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White
                        )
                        Text(
                            text = "${uiState.bundledNetlists.size} bundled Verilog/Bench benchmarks + parametric generators",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFCBD5E1)
                        )
                    }
                }
            }
        }

        // Quick Import / New Actions
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { openDocLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f).testTag("import_file_button")
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open .v/.bench")
                }
                OutlinedButton(
                    onClick = { showPasteDialog = true },
                    modifier = Modifier.weight(1f).testTag("paste_netlist_button")
                ) {
                    Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Paste Code")
                }
                OutlinedButton(
                    onClick = { showNewCircuitDialog = true },
                    modifier = Modifier.testTag("new_blank_circuit_button")
                ) {
                    Icon(Icons.Default.AddCircleOutline, contentDescription = "New circuit", modifier = Modifier.size(18.dp))
                }
            }
        }

        // Saved Local Circuits
        if (uiState.savedCircuits.isNotEmpty()) {
            item {
                Text("Saved Circuits", style = MaterialTheme.typography.titleMedium)
            }
            items(uiState.savedCircuits, key = { "saved_${it.id}" }) { saved ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.loadSavedCircuit(saved)
                            onNavigateToGraph()
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(saved.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "${saved.nodeCount} nodes • ${saved.edgeCount} edges • ${saved.format}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { viewModel.deleteSavedCircuit(saved.id) }) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = "Delete saved circuit",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }

        // Parametric Logic Builders (circuitgraph.logic)
        item {
            Text("Parametric Logic Generators (logic)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Half & Full Adder quick row
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BuilderActionCard(
                        title = "Half Adder",
                        subtitle = "2 in • AND + XOR",
                        onClick = {
                            viewModel.buildParametricLogic("half_adder", 1, false, false)
                            onNavigateToGraph()
                        },
                        modifier = Modifier.weight(1f).testTag("build_half_adder")
                    )
                    BuilderActionCard(
                        title = "Full Adder",
                        subtitle = "3 in • 2 HA + OR",
                        onClick = {
                            viewModel.buildParametricLogic("full_adder", 1, false, false)
                            onNavigateToGraph()
                        },
                        modifier = Modifier.weight(1f).testTag("build_full_adder")
                    )
                }

                // Ripple Carry Adder
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Ripple Carry Adder", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "Width: ${adderWidth.roundToInt()} bits",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Button(
                                onClick = {
                                    viewModel.buildParametricLogic(
                                        "adder",
                                        adderWidth.roundToInt(),
                                        adderCarryIn,
                                        adderCarryOut
                                    )
                                    onNavigateToGraph()
                                },
                                modifier = Modifier.testTag("build_adder_button")
                            ) {
                                Text("Build")
                            }
                        }
                        Slider(
                            value = adderWidth,
                            onValueChange = { adderWidth = it },
                            valueRange = 1f..8f,
                            steps = 6
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = adderCarryIn, onCheckedChange = { adderCarryIn = it })
                                Spacer(Modifier.width(6.dp))
                                Text("Carry In", style = MaterialTheme.typography.bodySmall)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = adderCarryOut, onCheckedChange = { adderCarryOut = it })
                                Spacer(Modifier.width(6.dp))
                                Text("Carry Out", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                // Mux & Popcount
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Multiplexer (${muxWidth.roundToInt()}:1)", style = MaterialTheme.typography.titleSmall)
                            Slider(
                                value = muxWidth,
                                onValueChange = { muxWidth = it },
                                valueRange = 2f..8f,
                                steps = 5
                            )
                            Button(
                                onClick = {
                                    viewModel.buildParametricLogic("mux", muxWidth.roundToInt(), false, false)
                                    onNavigateToGraph()
                                },
                                modifier = Modifier.fillMaxWidth().testTag("build_mux_button")
                            ) {
                                Text("Build Mux")
                            }
                        }
                    }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Popcount (${popWidth.roundToInt()}-bit)", style = MaterialTheme.typography.titleSmall)
                            Slider(
                                value = popWidth,
                                onValueChange = { popWidth = it },
                                valueRange = 2f..8f,
                                steps = 5
                            )
                            Button(
                                onClick = {
                                    viewModel.buildParametricLogic("popcount", popWidth.roundToInt(), false, false)
                                    onNavigateToGraph()
                                },
                                modifier = Modifier.fillMaxWidth().testTag("build_popcount_button")
                            ) {
                                Text("Build Pop")
                            }
                        }
                    }
                }
            }
        }

        // Bundled Benchmarks Section
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Bundled Netlist Library (from_lib)", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search netlists (e.g. c17, s27, mux_4, c432)") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("library_search_input")
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    categories.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat) }
                        )
                    }
                }
            }
        }

        items(filteredBundled, key = { it.fileName }) { item ->
            val isActive = uiState.activeCircuit.name == item.displayName
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isActive) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
                ),
                border = BorderStroke(
                    1.dp,
                    if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        viewModel.loadBundled(item.fileName)
                        onNavigateToGraph()
                    }
                    .testTag("bundled_item_${item.displayName}")
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            Icons.Default.Memory,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(item.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "${item.category} • ${item.fileName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = item.format,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }

    if (showPasteDialog) {
        PasteNetlistDialog(
            onDismiss = { showPasteDialog = false },
            onParse = { code, fmt ->
                viewModel.importNetlistText(code, formatHint = fmt)
                showPasteDialog = false
                onNavigateToGraph()
            }
        )
    }

    if (showNewCircuitDialog) {
        var name by remember { mutableStateOf("my_circuit") }
        AlertDialog(
            onDismissRequest = { showNewCircuitDialog = false },
            title = { Text("New Blank Circuit") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Module Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.createBlankCircuit(name)
                        showNewCircuitDialog = false
                        onNavigateToGraph()
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewCircuitDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun BuilderActionCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PasteNetlistDialog(
    onDismiss: () -> Unit,
    onParse: (code: String, formatHint: String?) -> Unit
) {
    var code by remember {
        mutableStateOf(
            """
            module demo(a, b, s, y);
              input a, b, s;
              output y;
              assign y = s ? b : a;
            endmodule
            """.trimIndent()
        )
    }
    var formatHint by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Parse Verilog or Bench") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = formatHint == null,
                        onClick = { formatHint = null },
                        label = { Text("Auto") }
                    )
                    FilterChip(
                        selected = formatHint == "verilog",
                        onClick = { formatHint = "verilog" },
                        label = { Text("Verilog") }
                    )
                    FilterChip(
                        selected = formatHint == "bench",
                        onClick = { formatHint = "bench" },
                        label = { Text("Bench") }
                    )
                }
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Netlist Source") },
                    minLines = 7,
                    maxLines = 12,
                    textStyle = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.fillMaxWidth().testTag("paste_netlist_field")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onParse(code, formatHint) },
                enabled = code.isNotBlank(),
                modifier = Modifier.testTag("parse_netlist_confirm")
            ) {
                Text("Parse & Load")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
