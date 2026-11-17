package com.circuitgraph.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.circuitgraph.app.core.CircuitIo
import com.circuitgraph.app.ui.CircuitUiState
import com.circuitgraph.app.ui.CircuitViewModel
import kotlin.math.roundToInt

private enum class CodeFormatTab(val label: String, val ext: String, val mime: String) {
    GATE_VERILOG("Gate Verilog", "v", "text/plain"),
    BEHAVIORAL_VERILOG("Behavioral Verilog", "v", "text/plain"),
    BENCH("Bench", "bench", "text/plain")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransformsScreen(
    uiState: CircuitUiState,
    viewModel: CircuitViewModel,
    onNavigateToGraph: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val circuit = uiState.activeCircuit

    var faninK by remember { mutableFloatStateOf(2f) }
    var fanoutK by remember { mutableFloatStateOf(2f) }
    var unrollCycles by remember { mutableFloatStateOf(3f) }
    var pipelineStages by remember { mutableFloatStateOf(2f) }

    var selectedFormat by remember { mutableStateOf(CodeFormatTab.GATE_VERILOG) }
    var copiedNotice by remember { mutableStateOf(false) }

    val generatedCode = remember(uiState.circuitVersion, selectedFormat) {
        try {
            when (selectedFormat) {
                CodeFormatTab.GATE_VERILOG -> CircuitIo.circuitToVerilog(circuit, behavioral = false)
                CodeFormatTab.BEHAVIORAL_VERILOG -> CircuitIo.circuitToVerilog(circuit, behavioral = true)
                CodeFormatTab.BENCH -> CircuitIo.circuitToBench(circuit)
            }
        } catch (e: Exception) {
            "// Cannot serialize to ${selectedFormat.label}: ${e.message}"
        }
    }

    var editableCode by remember(generatedCode) { mutableStateOf(generatedCode) }

    val exportDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    writer.write(editableCode)
                }
            } catch (_: Exception) {
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Circuit Transforms & Netlist I/O", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "Structural graph rewrites (tx) and bidirectional Verilog / Bench code serialization for '${circuit.name}'.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Structural Transforms
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("Graph Transformations (tx)", style = MaterialTheme.typography.titleMedium)

                    // Limit Fanin
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Limit Fanin (k = ${faninK.roundToInt()})", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "Decomposes multi-input gates into <= k-input trees.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    viewModel.applyTransform("limit_fanin", faninK.roundToInt())
                                    onNavigateToGraph()
                                },
                                modifier = Modifier.testTag("tx_limit_fanin_button")
                            ) { Text("Apply") }
                        }
                        Slider(
                            value = faninK,
                            onValueChange = { faninK = it },
                            valueRange = 2f..4f,
                            steps = 1
                        )
                    }

                    // Limit Fanout
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Limit Fanout (k = ${fanoutK.roundToInt()})", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "Inserts buffer trees where gate fanout exceeds k.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    viewModel.applyTransform("limit_fanout", fanoutK.roundToInt())
                                    onNavigateToGraph()
                                },
                                modifier = Modifier.testTag("tx_limit_fanout_button")
                            ) { Text("Apply") }
                        }
                        Slider(
                            value = fanoutK,
                            onValueChange = { fanoutK = it },
                            valueRange = 2f..6f,
                            steps = 3
                        )
                    }

                    // Sequential Unroll & Pipeline Register Insertion
                    if (circuit.blackboxes.isNotEmpty()) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Sequential Unroll (${unrollCycles.roundToInt()} cycles)", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = "Unrolls flip-flops across n clock cycles into a combinational DAG.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Button(
                                    onClick = {
                                        viewModel.applyTransform("seq_unroll", unrollCycles.roundToInt())
                                        onNavigateToGraph()
                                    },
                                    modifier = Modifier.testTag("tx_seq_unroll_button")
                                ) { Text("Unroll") }
                            }
                            Slider(
                                value = unrollCycles,
                                onValueChange = { unrollCycles = it },
                                valueRange = 1f..6f,
                                steps = 4
                            )
                        }
                    } else if (!circuit.isCyclic()) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Insert Pipeline FFs (${pipelineStages.roundToInt()} stages)", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = "Inserts clocked D-flip-flop blackboxes along depth cuts.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        viewModel.applyTransform("insert_regs", pipelineStages.roundToInt())
                                        onNavigateToGraph()
                                    },
                                    modifier = Modifier.testTag("tx_insert_regs_button")
                                ) { Text("Insert") }
                            }
                            Slider(
                                value = pipelineStages,
                                onValueChange = { pipelineStages = it },
                                valueRange = 1f..5f,
                                steps = 3
                            )
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TransformChipButton(
                            label = "Convert to AIG",
                            icon = Icons.Default.AccountTree,
                            enabled = circuit.blackboxes.isEmpty(),
                            testTag = "tx_to_aig_button",
                            onClick = {
                                viewModel.applyTransform("to_aig")
                                onNavigateToGraph()
                            }
                        )
                        TransformChipButton(
                            label = "Ternary (X-Sim)",
                            icon = Icons.Default.AutoFixHigh,
                            enabled = circuit.blackboxes.isEmpty(),
                            testTag = "tx_ternary_button",
                            onClick = {
                                viewModel.applyTransform("ternary")
                                onNavigateToGraph()
                            }
                        )
                        if (circuit.blackboxes.isNotEmpty()) {
                            TransformChipButton(
                                label = "Strip Blackboxes",
                                icon = Icons.Default.Memory,
                                enabled = true,
                                testTag = "tx_strip_bb_button",
                                onClick = {
                                    viewModel.applyTransform("strip_blackboxes")
                                    onNavigateToGraph()
                                }
                            )
                        }
                        if (circuit.isCyclic()) {
                            TransformChipButton(
                                label = "Acyclic Unroll",
                                icon = Icons.Default.Loop,
                                enabled = circuit.blackboxes.isEmpty(),
                                testTag = "tx_acyclic_unroll_button",
                                onClick = {
                                    viewModel.applyTransform("acyclic_unroll")
                                    onNavigateToGraph()
                                }
                            )
                        }
                        TransformChipButton(
                            label = "Prune Unloaded",
                            icon = Icons.Default.Layers,
                            enabled = true,
                            testTag = "tx_remove_unloaded_button",
                            onClick = {
                                viewModel.applyTransform("remove_unloaded")
                                onNavigateToGraph()
                            }
                        )
                    }
                }
            }
        }

        // Netlist Code Editor & Exporter
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Live Netlist Code & Export", style = MaterialTheme.typography.titleMedium)

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        CodeFormatTab.entries.forEach { tab ->
                            FilterChip(
                                selected = selectedFormat == tab,
                                onClick = {
                                    selectedFormat = tab
                                    copiedNotice = false
                                },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag("code_format_${tab.name.lowercase()}")
                            )
                        }
                    }

                    OutlinedTextField(
                        value = editableCode,
                        onValueChange = { editableCode = it },
                        textStyle = MaterialTheme.typography.labelMedium,
                        minLines = 10,
                        maxLines = 18,
                        label = { Text("${circuit.name}.${selectedFormat.ext}") },
                        modifier = Modifier.fillMaxWidth().testTag("netlist_code_editor")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(editableCode))
                                copiedNotice = true
                            },
                            modifier = Modifier.weight(1f).testTag("copy_netlist_button")
                        ) {
                            Icon(
                                if (copiedNotice) Icons.Default.Check else Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (copiedNotice) "Copied" else "Copy")
                        }
                        OutlinedButton(
                            onClick = {
                                exportDocLauncher.launch("${circuit.name}.${selectedFormat.ext}")
                            },
                            modifier = Modifier.weight(1f).testTag("export_netlist_button")
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Export")
                        }
                        Button(
                            onClick = {
                                val hint = if (selectedFormat == CodeFormatTab.BENCH) "bench" else "verilog"
                                viewModel.importNetlistText(editableCode, formatHint = hint)
                                onNavigateToGraph()
                            },
                            enabled = !editableCode.startsWith("// Cannot serialize"),
                            modifier = Modifier.weight(1f).testTag("apply_edited_netlist_button")
                        ) {
                            Text("Apply")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransformChipButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = if (enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
