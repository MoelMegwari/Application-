package com.circuitgraph.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.circuitgraph.app.ui.CircuitUiState
import com.circuitgraph.app.ui.CircuitViewModel
import com.circuitgraph.app.ui.theme.LocalCircuitColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnalysisScreen(
    uiState: CircuitUiState,
    viewModel: CircuitViewModel,
    modifier: Modifier = Modifier
) {
    val circuit = uiState.activeCircuit
    val stats = uiState.stats
    val semantic = LocalCircuitColors.current

    val startpoints = remember(uiState.circuitVersion) { circuit.startpoints().toList() }
    val outputs = remember(uiState.circuitVersion) { circuit.outputs().toList() }
    val inspectableNodes = remember(uiState.circuitVersion) {
        (outputs + circuit.orderedNodes().filter { it !in outputs && it !in startpoints }).distinct()
    }

    var selectedPropNode by remember(uiState.circuitVersion, uiState.selectedNode) {
        mutableStateOf(
            uiState.selectedNode?.takeIf { it in circuit }
                ?: outputs.firstOrNull()
                ?: circuit.orderedNodes().lastOrNull()
        )
    }

    val comparableNetlists = remember(uiState.bundledNetlists, circuit.name) {
        uiState.bundledNetlists.filter { it.format == "Verilog" }
    }
    var selectedMiterTarget by remember(comparableNetlists) {
        mutableStateOf(
            comparableNetlists.firstOrNull { it.displayName != circuit.name }?.fileName
                ?: comparableNetlists.firstOrNull()?.fileName
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header Summary
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "SAT, Probability & Verification",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "Tseitin CNF solver, #SAT signal probability, node sensitization, and equivalence miter for '${circuit.name}'.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (uiState.isBusy) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        // 1. SAT Solver & Tseitin CNF Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("SAT Solver (Tseitin CNF)", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Constrain outputs or internal nodes, then solve for a valid input vector.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (uiState.satAssumptions.isNotEmpty()) {
                            TextButton(
                                onClick = { viewModel.clearSatAssumptions() },
                                modifier = Modifier.testTag("sat_clear_assumptions_button")
                            ) {
                                Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Clear")
                            }
                        }
                    }

                    if (outputs.isNotEmpty()) {
                        Text("Output Constraints (tap to cycle ? → 1 → 0)", style = MaterialTheme.typography.labelSmall)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            outputs.take(24).forEach { out ->
                                val assumed = uiState.satAssumptions[out]
                                val nextVal: Boolean? = when (assumed) {
                                    null -> true
                                    true -> false
                                    false -> null
                                }
                                val chipLabel = when (assumed) {
                                    true -> "$out = 1"
                                    false -> "$out = 0"
                                    null -> "$out = ?"
                                }
                                val chipColor = when (assumed) {
                                    true -> semantic.logicOne
                                    false -> MaterialTheme.colorScheme.error
                                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                FilterChip(
                                    selected = assumed != null,
                                    onClick = { viewModel.toggleSatAssumption(out, nextVal) },
                                    label = { Text(chipLabel, style = MaterialTheme.typography.labelMedium) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = chipColor.copy(alpha = 0.18f),
                                        selectedLabelColor = chipColor
                                    ),
                                    modifier = Modifier.testTag("sat_assume_$out")
                                )
                            }
                        }
                    }

                    if (startpoints.isNotEmpty()) {
                        Text("Input Constraints (optional)", style = MaterialTheme.typography.labelSmall)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            startpoints.take(20).forEach { inp ->
                                val assumed = uiState.satAssumptions[inp]
                                val nextVal: Boolean? = when (assumed) {
                                    null -> true
                                    true -> false
                                    false -> null
                                }
                                val label = when (assumed) {
                                    true -> "$inp=1"
                                    false -> "$inp=0"
                                    null -> "$inp=?"
                                }
                                FilterChip(
                                    selected = assumed != null,
                                    onClick = { viewModel.toggleSatAssumption(inp, nextVal) },
                                    label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                                )
                            }
                        }
                    }

                    Button(
                        onClick = { viewModel.runSatSolve() },
                        enabled = !uiState.isBusy,
                        modifier = Modifier.fillMaxWidth().testTag("run_sat_button")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Solve CNF (${uiState.satAssumptions.size} assumptions)")
                    }

                    uiState.satResult?.let { res ->
                        val statusColor = if (res.satisfiable) semantic.logicOne else MaterialTheme.colorScheme.error
                        Surface(
                            color = statusColor.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            if (res.satisfiable) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                            contentDescription = if (res.satisfiable) "Satisfiable" else "Unsatisfiable",
                                            tint = statusColor,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = if (res.satisfiable) "SATISFIABLE" else "UNSATISFIABLE",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = statusColor
                                        )
                                    }
                                    Text(
                                        text = "${res.solveTimeMs} ms • ${res.numVars} vars • ${res.numClauses} clauses",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (res.assignment != null) {
                                    val inputVector = startpoints.joinToString(", ") { inp ->
                                        "$inp=${if (res.assignment[inp] == true) 1 else 0}"
                                    }
                                    val outputVector = outputs.joinToString(", ") { out ->
                                        "$out=${if (res.assignment[out] == true) 1 else 0}"
                                    }
                                    if (inputVector.isNotEmpty()) {
                                        Text(
                                            text = "Witness Inputs: $inputVector",
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                    if (outputVector.isNotEmpty()) {
                                        Text(
                                            text = "Witness Outputs: $outputVector",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Signal Probability (#SAT) & Node Sensitivity Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Node Signal Probability & Sensitization", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Select a target node to compute #SAT P(node=1), observability sensitizing vectors, and input influences.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (inspectableNodes.isEmpty()) {
                        Text(
                            text = "Add internal gates or outputs to analyze node properties.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            inspectableNodes.take(28).forEach { node ->
                                FilterChip(
                                    selected = selectedPropNode == node,
                                    onClick = { selectedPropNode = node },
                                    label = { Text(node, style = MaterialTheme.typography.labelMedium) }
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { selectedPropNode?.let { viewModel.computeSignalProbability(it) } },
                                enabled = selectedPropNode != null && !uiState.isBusy,
                                modifier = Modifier.weight(1f).testTag("compute_prob_button")
                            ) {
                                Text("#SAT Prob")
                            }
                            Button(
                                onClick = { selectedPropNode?.let { viewModel.analyzeNode(it) } },
                                enabled = selectedPropNode != null && !uiState.isBusy,
                                modifier = Modifier.weight(1f).testTag("analyze_sensitivity_button")
                            ) {
                                Icon(Icons.Default.Analytics, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Full Report")
                            }
                        }
                    }

                    uiState.modelCountResult?.let { mc ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Node '${uiState.modelCountTargetNode}': P = ${"%.5f".format(mc.probability)} " +
                                        if (mc.isExact) "(Exact #SAT)" else "(Monte Carlo 1024 samples)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Cone input space: ${mc.totalInputSpace.toLong().coerceAtLeast(1L)} vectors",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    uiState.nodeReport?.let { rep ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Sensitivity Report: ${rep.node}",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "Signal Probability: ${"%.5f".format(rep.signalProbability)} " +
                                        if (rep.isExactProbability) "(exact)" else "(Monte Carlo)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                Text(
                                    text = if (rep.sensitizingInput != null) {
                                        val vec = rep.sensitizingInput.entries.joinToString(", ") {
                                            "${it.key}=${if (it.value) 1 else 0}"
                                        }
                                        "Sensitizing Input Vector: $vec"
                                    } else {
                                        "Sensitizing Vector: Not sensitizable (or sequential/blackbox)"
                                    },
                                    style = MaterialTheme.typography.labelSmall
                                )
                                if (rep.influences.isNotEmpty()) {
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                                    Text(
                                        text = "Average Sensitivity: ${"%.4f".format(rep.averageSensitivity)}",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        rep.influences.forEach { (inp, inf) ->
                                            Surface(
                                                color = MaterialTheme.colorScheme.surface,
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    text = "Inf($inp)=${"%.3f".format(inf)}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Combinational Equivalence Miter Card
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
                    Text("Equivalence Checking (Miter SAT)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Builds a miter circuit XOR-ing shared outputs against a reference Verilog benchmark and checks UNSAT.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        comparableNetlists.forEach { item ->
                            FilterChip(
                                selected = selectedMiterTarget == item.fileName,
                                onClick = { selectedMiterTarget = item.fileName },
                                label = { Text(item.displayName, style = MaterialTheme.typography.labelMedium) }
                            )
                        }
                    }

                    Button(
                        onClick = { selectedMiterTarget?.let { viewModel.runEquivalenceMiter(it) } },
                        enabled = selectedMiterTarget != null && !uiState.isBusy && circuit.blackboxes.isEmpty(),
                        modifier = Modifier.fillMaxWidth().testTag("run_miter_button")
                    ) {
                        Icon(Icons.Default.CompareArrows, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (circuit.blackboxes.isNotEmpty()) {
                                "Strip Blackboxes Before Miter"
                            } else {
                                "Verify Equivalence vs ${selectedMiterTarget ?: "-"}"
                            }
                        )
                    }

                    uiState.miterResult?.let { mr ->
                        val color = if (mr.equivalent) semantic.logicOne else MaterialTheme.colorScheme.error
                        Surface(
                            color = color.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (mr.equivalent) Icons.Default.Verified else Icons.Default.WarningAmber,
                                        contentDescription = null,
                                        tint = color,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = if (mr.equivalent) {
                                            "EQUIVALENT to ${mr.comparedWith} (UNSAT)"
                                        } else {
                                            "NOT EQUIVALENT to ${mr.comparedWith}"
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        color = color
                                    )
                                }
                                Text(
                                    text = "Miter SAT runtime: ${mr.solveTimeMs} ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                mr.counterexample?.let { cx ->
                                    val cxStr = cx.entries.take(10).joinToString(", ") {
                                        "${it.key}=${if (it.value) 1 else 0}"
                                    }
                                    Text(
                                        text = "Counterexample: $cxStr" + if (cx.size > 10) "…" else "",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Structural Diagnostics & Lint Report Card
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Netlist Lint & Topology", style = MaterialTheme.typography.titleMedium)
                        val isClean = uiState.lintReport?.isValid != false
                        Surface(
                            color = (if (isClean) semantic.logicOne else MaterialTheme.colorScheme.error).copy(alpha = 0.16f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (isClean) "LINT PASS" else "${uiState.lintReport?.errors?.size ?: 0} ERRORS",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isClean) semantic.logicOne else MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    if (stats != null) {
                        Text(
                            text = "Cyclic: ${if (stats.isCyclic) "Yes" else "No (DAG)"} • " +
                                "Reconvergent Fanout: ${if (stats.hasReconvergentFanout) "Yes" else "No"} • " +
                                "Max Depth: ${stats.maxDepth}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (stats.gateHistogram.isNotEmpty()) {
                            Text("Gate Breakdown", style = MaterialTheme.typography.labelSmall)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                stats.gateHistogram.forEach { (type, count) ->
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = "${type.uppercase()}: $count",
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    uiState.lintReport?.let { report ->
                        report.errors.take(6).forEach { err ->
                            Text(
                                text = "• Error: $err",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (report.warnings.isNotEmpty()) {
                            Text(
                                text = "${report.warnings.size} unloaded/floating warnings (e.g., ${report.warnings.first()})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                            OutlinedButton(
                                onClick = { viewModel.removeUnloaded() },
                                modifier = Modifier.testTag("analysis_prune_unloaded_button")
                            ) {
                                Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Prune Unloaded Nodes")
                            }
                        }
                    }
                }
            }
        }
    }
}
