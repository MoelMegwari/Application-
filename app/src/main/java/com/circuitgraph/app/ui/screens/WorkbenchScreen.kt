package com.circuitgraph.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Output
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circuitgraph.app.core.GateTypes
import com.circuitgraph.app.core.LogicValue
import com.circuitgraph.app.ui.CircuitUiState
import com.circuitgraph.app.ui.CircuitViewModel
import com.circuitgraph.app.ui.WorkbenchMode
import com.circuitgraph.app.ui.components.CircuitGraphCanvas
import com.circuitgraph.app.ui.components.CycleValueInspector
import com.circuitgraph.app.ui.components.WaveformViewer
import com.circuitgraph.app.ui.theme.LocalCircuitColors

@Composable
fun WorkbenchScreen(
    uiState: CircuitUiState,
    viewModel: CircuitViewModel,
    isExpanded: Boolean,
    modifier: Modifier = Modifier
) {
    var showAddNodeDialog by remember { mutableStateOf(false) }
    var showConnectDialog by remember { mutableStateOf(false) }
    var inspectorExpanded by remember { mutableStateOf(false) }

    val circuit = uiState.activeCircuit
    val stats = uiState.stats

    Column(modifier = modifier.fillMaxSize()) {
        // Top Stats & Quick Action Bar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = circuit.name,
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (stats != null) {
                            Text(
                                text = "${stats.nodeCount} nodes • ${stats.edgeCount} edges • depth ${stats.maxDepth}" +
                                    if (stats.blackboxCount > 0) " • ${stats.blackboxCount} FF/BB" else "",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(
                            onClick = { viewModel.undo() },
                            enabled = uiState.canUndo,
                            modifier = Modifier.testTag("undo_button")
                        ) {
                            Icon(Icons.Default.Undo, contentDescription = "Undo")
                        }
                        IconButton(
                            onClick = { viewModel.saveCurrentCircuit() },
                            modifier = Modifier.testTag("save_circuit_button")
                        ) {
                            Icon(Icons.Default.Save, contentDescription = "Save circuit")
                        }
                        IconButton(
                            onClick = { showConnectDialog = true },
                            modifier = Modifier.testTag("connect_edge_button")
                        ) {
                            Icon(Icons.Default.Link, contentDescription = "Connect nodes")
                        }
                        IconButton(
                            onClick = { showAddNodeDialog = true },
                            modifier = Modifier.testTag("add_node_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add node")
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SegmentedButton(
                        selected = uiState.workbenchMode == WorkbenchMode.GRAPH,
                        onClick = { viewModel.setWorkbenchMode(WorkbenchMode.GRAPH) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        icon = {
                            Icon(Icons.Default.AccountTree, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier.testTag("mode_graph_button")
                    ) {
                        Text("DAG Graph")
                    }
                    SegmentedButton(
                        selected = uiState.workbenchMode == WorkbenchMode.WAVEFORM,
                        onClick = { viewModel.setWorkbenchMode(WorkbenchMode.WAVEFORM) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        icon = {
                            Icon(Icons.Default.Waves, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier.testTag("mode_waveform_button")
                    ) {
                        Text(
                            if (uiState.waveformData != null) {
                                "Waveform (${uiState.waveformData.cycleCount} cyc)"
                            } else {
                                "Waveform"
                            }
                        )
                    }
                }

                if (stats?.isCyclic == true) {
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Cyclic graph detected — use Acyclic Unroll in Tx tab to break feedback loops",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }

        // Main Split or Stacked Workspace
        val showWaveform = uiState.workbenchMode == WorkbenchMode.WAVEFORM
        val hasWaveformData = uiState.waveformData != null
        val canvasCircuit = if (showWaveform && hasWaveformData && uiState.unrollSourceCircuit != null) {
            uiState.unrollSourceCircuit
        } else {
            circuit
        }
        val canvasLevels = if (showWaveform && hasWaveformData && uiState.unrollSourceCircuit != null) {
            uiState.unrollSourceLevels
        } else {
            uiState.levels
        }
        val canvasValues = if (showWaveform && hasWaveformData && uiState.unrollSourceCircuit != null) {
            uiState.cycleSnapshotValues
        } else {
            uiState.simulationValues
        }
        val canvasHighlighted = if (hasWaveformData && !(showWaveform && uiState.unrollSourceCircuit != null)) {
            uiState.activeCycleNodes
        } else {
            emptySet()
        }
        val canvasBadge = if (hasWaveformData) {
            "T = ${uiState.activeCycleIndex} / ${(uiState.unrollCycleCount - 1).coerceAtLeast(0)}"
        } else {
            null
        }

        if (isExpanded) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (showWaveform) {
                    Column(modifier = Modifier.weight(1.45f).fillMaxHeight()) {
                        if (hasWaveformData) {
                            CircuitGraphCanvas(
                                circuit = canvasCircuit,
                                circuitVersion = uiState.circuitVersion,
                                levels = canvasLevels,
                                selectedNode = uiState.selectedNode,
                                simulationValues = canvasValues,
                                onNodeSelected = viewModel::selectNode,
                                highlightedNodes = canvasHighlighted,
                                cycleBadge = canvasBadge,
                                modifier = Modifier
                                    .weight(if (inspectorExpanded) 0.65f else 0.9f)
                                    .fillMaxWidth()
                            )
                        }
                        WaveformViewer(
                            circuit = circuit,
                            waveformData = uiState.waveformData,
                            activeCycle = uiState.activeCycleIndex,
                            onCycleChanged = viewModel::setActiveCycle,
                            selectedNode = uiState.selectedNode,
                            onToggleCycleInput = viewModel::cycleInputValue,
                            onSelectNode = viewModel::selectNode,
                            onAllZero = { viewModel.setAllInputs(LogicValue.ZERO) },
                            onAlternatingStimulus = viewModel::applyAlternatingStimulus,
                            onRandomizeInputs = viewModel::randomizeInputs,
                            onUnrollSequential = viewModel::unrollSequentialForWaveform,
                            onLoadSampleSequential = { viewModel.loadBundled("s27.v") },
                            onInsertPipelineAndUnroll = viewModel::insertPipelineAndUnrollForWaveform,
                            modifier = Modifier
                                .weight(
                                    if (!hasWaveformData) 1f
                                    else if (inspectorExpanded) 0.75f else 0.95f
                                )
                                .fillMaxWidth()
                        )
                        if (hasWaveformData) {
                            CycleValueInspector(
                                activeCircuit = circuit,
                                unrollSourceCircuit = uiState.unrollSourceCircuit,
                                unrollIoMap = uiState.unrollIoMap,
                                activeCycle = uiState.activeCycleIndex,
                                cycleSnapshotValues = uiState.cycleSnapshotValues,
                                simulationValues = uiState.simulationValues,
                                activeCycleNodes = uiState.activeCycleNodes,
                                selectedNode = uiState.selectedNode,
                                expanded = inspectorExpanded,
                                onToggleExpand = { inspectorExpanded = !inspectorExpanded },
                                onSelectNode = viewModel::selectNode,
                                onToggleCycleInput = viewModel::cycleInputValue,
                                modifier = Modifier
                                    .weight(if (inspectorExpanded) 1.3f else 0.85f)
                                    .fillMaxWidth()
                            )
                        }
                    }
                } else {
                    CircuitGraphCanvas(
                        circuit = canvasCircuit,
                        circuitVersion = uiState.circuitVersion,
                        levels = canvasLevels,
                        selectedNode = uiState.selectedNode,
                        simulationValues = canvasValues,
                        onNodeSelected = viewModel::selectNode,
                        highlightedNodes = canvasHighlighted,
                        cycleBadge = canvasBadge,
                        modifier = Modifier.weight(1.45f).fillMaxHeight()
                    )
                }
                SimulationAndInspectorPanel(
                    uiState = uiState,
                    viewModel = viewModel,
                    onOpenAddNode = { showAddNodeDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                )
            }
        } else if (showWaveform) {
            if (hasWaveformData) {
                Box(
                    modifier = Modifier
                        .weight(if (inspectorExpanded) 0.50f else 0.72f)
                        .fillMaxWidth()
                ) {
                    CircuitGraphCanvas(
                        circuit = canvasCircuit,
                        circuitVersion = uiState.circuitVersion,
                        levels = canvasLevels,
                        selectedNode = uiState.selectedNode,
                        simulationValues = canvasValues,
                        onNodeSelected = viewModel::selectNode,
                        highlightedNodes = canvasHighlighted,
                        cycleBadge = canvasBadge,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            WaveformViewer(
                circuit = circuit,
                waveformData = uiState.waveformData,
                activeCycle = uiState.activeCycleIndex,
                onCycleChanged = viewModel::setActiveCycle,
                selectedNode = uiState.selectedNode,
                onToggleCycleInput = viewModel::cycleInputValue,
                onSelectNode = viewModel::selectNode,
                onAllZero = { viewModel.setAllInputs(LogicValue.ZERO) },
                onAlternatingStimulus = viewModel::applyAlternatingStimulus,
                onRandomizeInputs = viewModel::randomizeInputs,
                onUnrollSequential = viewModel::unrollSequentialForWaveform,
                onLoadSampleSequential = { viewModel.loadBundled("s27.v") },
                onInsertPipelineAndUnroll = viewModel::insertPipelineAndUnrollForWaveform,
                modifier = Modifier
                    .weight(
                        if (!hasWaveformData) 1f
                        else if (inspectorExpanded) 0.78f else 1.05f
                    )
                    .fillMaxWidth()
            )
            if (hasWaveformData) {
                CycleValueInspector(
                    activeCircuit = circuit,
                    unrollSourceCircuit = uiState.unrollSourceCircuit,
                    unrollIoMap = uiState.unrollIoMap,
                    activeCycle = uiState.activeCycleIndex,
                    cycleSnapshotValues = uiState.cycleSnapshotValues,
                    simulationValues = uiState.simulationValues,
                    activeCycleNodes = uiState.activeCycleNodes,
                    selectedNode = uiState.selectedNode,
                    expanded = inspectorExpanded,
                    onToggleExpand = { inspectorExpanded = !inspectorExpanded },
                    onSelectNode = viewModel::selectNode,
                    onToggleCycleInput = viewModel::cycleInputValue,
                    modifier = Modifier
                        .weight(if (inspectorExpanded) 1.35f else 0.85f)
                        .fillMaxWidth()
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .weight(1.15f)
                    .fillMaxWidth()
            ) {
                CircuitGraphCanvas(
                    circuit = canvasCircuit,
                    circuitVersion = uiState.circuitVersion,
                    levels = canvasLevels,
                    selectedNode = uiState.selectedNode,
                    simulationValues = canvasValues,
                    onNodeSelected = viewModel::selectNode,
                    highlightedNodes = canvasHighlighted,
                    cycleBadge = canvasBadge,
                    modifier = Modifier.fillMaxSize()
                )
            }
            SimulationAndInspectorPanel(
                uiState = uiState,
                viewModel = viewModel,
                onOpenAddNode = { showAddNodeDialog = true },
                modifier = Modifier
                    .weight(0.95f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }

    if (showAddNodeDialog) {
        AddNodeDialog(
            existingNodes = circuit.orderedNodes(),
            onDismiss = { showAddNodeDialog = false },
            onConfirm = { name, type, fanin, isOutput ->
                viewModel.addNode(name, type, fanin, isOutput)
                showAddNodeDialog = false
            }
        )
    }

    if (showConnectDialog) {
        ConnectNodesDialog(
            nodes = circuit.orderedNodes(),
            onDismiss = { showConnectDialog = false },
            onConfirm = { from, to ->
                viewModel.connectNodes(from, to)
                showConnectDialog = false
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SimulationAndInspectorPanel(
    uiState: CircuitUiState,
    viewModel: CircuitViewModel,
    onOpenAddNode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val circuit = uiState.activeCircuit
    val semantic = LocalCircuitColors.current
    val startpoints = remember(uiState.circuitVersion) { circuit.startpoints().toList() }
    val outputs = remember(uiState.circuitVersion) { circuit.outputs().toList() }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Selected Node Inspector Card
        AnimatedVisibility(visible = uiState.selectedNode != null && uiState.selectedNode in circuit) {
            val node = uiState.selectedNode
            if (node != null && node in circuit) {
                val nType = circuit.type(node)
                val isOut = circuit.isOutput(node)
                val level = uiState.levels[node]
                val fanin = circuit.fanin(node).toList()
                val fanout = circuit.fanout(node).toList()
                val simVal = uiState.simulationValues[node] ?: LogicValue.X

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Node: $node",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "type=${nType.uppercase()} • val=${simVal.symbol}" +
                                        (if (level != null) " • level=$level" else "") +
                                        (if (isOut) " • PRIMARY OUT" else ""),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(
                                onClick = { viewModel.selectNode(null) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close inspector")
                            }
                        }

                        if (fanin.isNotEmpty()) {
                            Text("Fanin (${fanin.size})", style = MaterialTheme.typography.labelSmall)
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                fanin.forEach { fi ->
                                    AssistChip(
                                        onClick = { viewModel.selectNode(fi) },
                                        label = { Text("$fi (${uiState.simulationValues[fi]?.symbol ?: "X"})") }
                                    )
                                }
                            }
                        }

                        if (fanout.isNotEmpty()) {
                            Text("Fanout (${fanout.size})", style = MaterialTheme.typography.labelSmall)
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                fanout.forEach { fo ->
                                    AssistChip(
                                        onClick = { viewModel.selectNode(fo) },
                                        label = { Text("$fo (${uiState.simulationValues[fo]?.symbol ?: "X"})") }
                                    )
                                }
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.analyzeNode(node) },
                                modifier = Modifier.weight(1f).testTag("inspect_analyze_button")
                            ) {
                                Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Analyze")
                            }
                            OutlinedButton(
                                onClick = { viewModel.toggleNodeOutput(node) },
                                modifier = Modifier.weight(1f).testTag("inspect_toggle_output_button")
                            ) {
                                Icon(Icons.Default.Output, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (isOut) "Unset Out" else "Set Out")
                            }
                            IconButton(
                                onClick = { viewModel.removeNode(node) },
                                modifier = Modifier.testTag("inspect_delete_node_button")
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "Delete node",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        uiState.nodeReport?.takeIf { it.node == node }?.let { rep ->
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "P($node = 1) = ${"%.4f".format(rep.signalProbability)} " +
                                            if (rep.isExactProbability) "(exact)" else "(est.)",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                    Text(
                                        text = if (rep.sensitizingInput != null) {
                                            val vec = rep.sensitizingInput.entries.take(6)
                                                .joinToString(", ") { "${it.key}=${if (it.value) 1 else 0}" }
                                            "Sensitizing vector: $vec" + if (rep.sensitizingInput.size > 6) "…" else ""
                                        } else {
                                            "Not sensitizable to outputs (or sequential)"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (rep.influences.isNotEmpty()) {
                                        Text(
                                            text = "Avg sensitivity: ${"%.3f".format(rep.averageSensitivity)}",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Interactive Logic Simulator Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Live Logic Simulator", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(
                            onClick = { viewModel.setAllInputs(LogicValue.ZERO) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("sim_all_zero_button")
                        ) {
                            Text("All 0")
                        }
                        TextButton(
                            onClick = { viewModel.randomizeInputs() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("sim_random_button")
                        ) {
                            Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Rand")
                        }
                    }
                }

                if (startpoints.isEmpty()) {
                    Text(
                        text = "No inputs or flip-flop outputs in this circuit.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Tap inputs to cycle 0 → 1 → X",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        startpoints.take(32).forEach { inp ->
                            val v = uiState.simulationInputs[inp] ?: LogicValue.X
                            val chipColor = when (v) {
                                LogicValue.ONE -> semantic.logicOne
                                LogicValue.ZERO -> semantic.logicZero
                                LogicValue.X -> semantic.logicX
                            }
                            FilterChip(
                                selected = v == LogicValue.ONE,
                                onClick = { viewModel.cycleInputValue(inp) },
                                label = {
                                    Text(
                                        text = "$inp=${v.symbol}",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = chipColor.copy(alpha = 0.2f),
                                    selectedLabelColor = chipColor
                                ),
                                modifier = Modifier.testTag("sim_input_$inp")
                            )
                        }
                    }
                    if (startpoints.size > 32) {
                        Text(
                            text = "Showing first 32 of ${startpoints.size} inputs",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (outputs.isNotEmpty()) {
                    Text("Primary Outputs", style = MaterialTheme.typography.labelSmall)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        outputs.take(32).forEach { out ->
                            val v = uiState.simulationValues[out] ?: LogicValue.X
                            val valColor = when (v) {
                                LogicValue.ONE -> semantic.logicOne
                                LogicValue.ZERO -> semantic.logicZero
                                LogicValue.X -> semantic.logicX
                            }
                            Surface(
                                color = valColor.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, valColor.copy(alpha = 0.6f)),
                                modifier = Modifier.clickable { viewModel.selectNode(out) }
                            ) {
                                Text(
                                    text = "$out = ${v.symbol}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = valColor,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Quick Edit / Clean row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onOpenAddNode,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add Gate")
            }
            OutlinedButton(
                onClick = { viewModel.removeUnloaded() },
                modifier = Modifier.weight(1f).testTag("clean_unloaded_button")
            ) {
                Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Prune Unloaded")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddNodeDialog(
    existingNodes: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, type: String, fanin: List<String>, isOutput: Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf("and") }
    var isOutput by remember { mutableStateOf(false) }
    val selectedFanin = remember { mutableStateListOf<String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Circuit Node") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Node Name (e.g. g_new)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("add_node_name_input")
                )

                Text("Gate / Node Type", style = MaterialTheme.typography.labelSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    GateTypes.addableTypes.forEach { t ->
                        FilterChip(
                            selected = selectedType == t,
                            onClick = {
                                selectedType = t
                                if (t in listOf("input", "0", "1", "x")) selectedFanin.clear()
                            },
                            label = { Text(t.uppercase()) }
                        )
                    }
                }

                if (selectedType !in listOf("input", "0", "1", "x")) {
                    Text("Select Fanin Nodes", style = MaterialTheme.typography.labelSmall)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        existingNodes.take(24).forEach { n ->
                            val isSel = n in selectedFanin
                            FilterChip(
                                selected = isSel,
                                onClick = {
                                    if (isSel) {
                                        selectedFanin.remove(n)
                                    } else {
                                        if (selectedType in listOf("buf", "not")) selectedFanin.clear()
                                        selectedFanin.add(n)
                                    }
                                },
                                label = { Text(n) }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Mark as Primary Output")
                    Switch(checked = isOutput, onCheckedChange = { isOutput = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, selectedType, selectedFanin.toList(), isOutput) },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("confirm_add_node_button")
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ConnectNodesDialog(
    nodes: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (from: String, to: String) -> Unit
) {
    var fromNode by remember { mutableStateOf(nodes.firstOrNull() ?: "") }
    var toNode by remember { mutableStateOf(nodes.lastOrNull() ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect Nodes") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = fromNode,
                    onValueChange = { fromNode = it },
                    label = { Text("Driver (Source) Node") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = toNode,
                    onValueChange = { toNode = it },
                    label = { Text("Target Gate Node") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Available: ${nodes.take(12).joinToString(", ")}${if (nodes.size > 12) "…" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(fromNode.trim(), toNode.trim()) },
                enabled = fromNode.isNotBlank() && toNode.isNotBlank()
            ) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
