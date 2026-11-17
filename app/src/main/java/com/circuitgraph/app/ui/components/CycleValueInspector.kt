package com.circuitgraph.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.circuitgraph.app.core.Circuit
import com.circuitgraph.app.core.LogicValue
import com.circuitgraph.app.ui.theme.LocalCircuitColors

enum class WireCategory(val order: Int) {
    INPUT(0),
    FF(1),
    OUTPUT(2),
    INTERNAL(3)
}

enum class WireInspectorFilter(val label: String) {
    ALL("All"),
    INPUTS("Inputs"),
    FFS("FFs"),
    OUTPUTS("Outputs"),
    INTERNAL("Internal"),
    CHANGED("Changed")
}

data class WireCycleRow(
    val wireName: String,
    val circuitNodeName: String,
    val editableCycleNode: String?,
    val roleLabel: String,
    val category: WireCategory,
    val value: LogicValue,
    val prevValue: LogicValue?,
    val hasChanged: Boolean
)

object CycleInspectorExtractor {
    private val unrolledPrefixRegex = Regex("""^unrolled_(\d+)_(.+)$""")
    private val cgUnrollRegex = Regex("""^(.+)_cg_unroll_(\d+)$""")

    fun extractRows(
        activeCircuit: Circuit,
        unrollSourceCircuit: Circuit?,
        unrollIoMap: Map<String, List<String>>,
        activeCycle: Int,
        cycleSnapshotValues: Map<String, LogicValue>,
        simulationValues: Map<String, LogicValue>,
        activeCycleNodes: Set<String>
    ): List<WireCycleRow> {
        val rows = mutableListOf<WireCycleRow>()

        if (unrollSourceCircuit != null) {
            val prevSnapshot = if (activeCycle > 0) {
                WaveformExtractor.projectCycleValues(
                    sourceCircuit = unrollSourceCircuit,
                    ioMap = unrollIoMap,
                    cycle = activeCycle - 1,
                    unrolledValues = simulationValues
                )
            } else {
                null
            }

            for (node in unrollSourceCircuit.orderedNodes()) {
                val nodeType = unrollSourceCircuit.type(node)
                val isOutput = unrollSourceCircuit.isOutput(node)
                val (category, roleLabel) = classifyNode(node, nodeType, isOutput)

                val value = cycleSnapshotValues[node] ?: LogicValue.X
                val prevValue = prevSnapshot?.get(node)
                val hasChanged = prevValue != null && prevValue != value

                val editableNode = resolveCycleNode(node, activeCycle, unrollIoMap, activeCircuit)
                    ?.takeIf { it in activeCircuit && activeCircuit.type(it) == "input" }

                rows.add(
                    WireCycleRow(
                        wireName = node,
                        circuitNodeName = node,
                        editableCycleNode = editableNode,
                        roleLabel = roleLabel,
                        category = category,
                        value = value,
                        prevValue = prevValue,
                        hasChanged = hasChanged
                    )
                )
            }
        } else {
            // Pre-unrolled netlists loaded directly (e.g., s27_unrolled_4.v)
            val seenCircuitNodes = HashSet<String>()

            for ((baseName, cycleNodes) in unrollIoMap) {
                val cycleNode = cycleNodes.getOrNull(activeCycle)?.takeIf {
                    it.isNotEmpty() && it in activeCircuit
                } ?: continue
                if (!seenCircuitNodes.add(cycleNode)) continue

                val nodeType = activeCircuit.type(cycleNode)
                val isOutput = activeCircuit.isOutput(cycleNode)
                val (category, roleLabel) = classifyNode(baseName, nodeType, isOutput)
                val value = simulationValues[cycleNode] ?: LogicValue.X
                val prevNode = if (activeCycle > 0) {
                    cycleNodes.getOrNull(activeCycle - 1)?.takeIf {
                        it.isNotEmpty() && it in activeCircuit
                    }
                } else {
                    null
                }
                val prevValue = prevNode?.let { simulationValues[it] ?: LogicValue.X }
                val editableNode = cycleNode.takeIf { nodeType == "input" }

                rows.add(
                    WireCycleRow(
                        wireName = baseName,
                        circuitNodeName = cycleNode,
                        editableCycleNode = editableNode,
                        roleLabel = roleLabel,
                        category = category,
                        value = value,
                        prevValue = prevValue,
                        hasChanged = prevValue != null && prevValue != value
                    )
                )
            }

            val candidateNodes = if (activeCycleNodes.isNotEmpty()) {
                activeCircuit.orderedNodes().filter { it in activeCycleNodes }
            } else {
                activeCircuit.orderedNodes()
            }

            for (node in candidateNodes) {
                if (!seenCircuitNodes.add(node)) continue
                val baseName = stripCycleAffix(node, activeCycle)
                val nodeType = activeCircuit.type(node)
                val isOutput = activeCircuit.isOutput(node)
                val (category, roleLabel) = classifyNode(baseName, nodeType, isOutput)
                val value = simulationValues[node] ?: LogicValue.X
                val prevNode = if (activeCycle > 0) {
                    findSiblingCycleNode(node, activeCycle, activeCycle - 1, activeCircuit)
                } else {
                    null
                }
                val prevValue = prevNode?.let { simulationValues[it] ?: LogicValue.X }
                val editableNode = node.takeIf { nodeType == "input" }

                rows.add(
                    WireCycleRow(
                        wireName = baseName,
                        circuitNodeName = node,
                        editableCycleNode = editableNode,
                        roleLabel = roleLabel,
                        category = category,
                        value = value,
                        prevValue = prevValue,
                        hasChanged = prevValue != null && prevValue != value
                    )
                )
            }
        }

        return rows.sortedWith(
            compareBy<WireCycleRow> { it.category.order }
                .thenBy { it.wireName.lowercase() }
        )
    }

    private fun classifyNode(
        wireName: String,
        nodeType: String,
        isOutput: Boolean
    ): Pair<WireCategory, String> {
        return when {
            nodeType == "input" -> WireCategory.INPUT to "IN"
            nodeType == "bb_output" ||
                wireName.endsWith(".q", ignoreCase = true) ||
                wireName.endsWith("_Q", ignoreCase = true) ||
                wireName.contains("_reg_Q", ignoreCase = true) -> WireCategory.FF to "FF.Q"
            nodeType == "bb_input" ||
                wireName.endsWith(".d", ignoreCase = true) ||
                wireName.endsWith("_D", ignoreCase = true) ||
                wireName.contains("_reg_D", ignoreCase = true) -> WireCategory.FF to "FF.D"
            isOutput -> WireCategory.OUTPUT to "OUT"
            else -> WireCategory.INTERNAL to nodeType.uppercase()
        }
    }

    private fun resolveCycleNode(
        sourceNode: String,
        cycle: Int,
        ioMap: Map<String, List<String>>,
        activeCircuit: Circuit
    ): String? {
        val fromIo = ioMap[sourceNode]?.getOrNull(cycle)
        if (!fromIo.isNullOrEmpty() && fromIo in activeCircuit) return fromIo

        val unrolledPref = "unrolled_${cycle}_$sourceNode"
        if (unrolledPref in activeCircuit) return unrolledPref

        val cgSuffix = "${sourceNode}_cg_unroll_$cycle"
        if (cgSuffix in activeCircuit) return cgSuffix

        if ('.' in sourceNode) {
            val bbKey = sourceNode.replace('.', '_')
            val bbIo = ioMap[bbKey]?.getOrNull(cycle)
            if (!bbIo.isNullOrEmpty() && bbIo in activeCircuit) return bbIo
            val bbPref = "unrolled_${cycle}_$bbKey"
            if (bbPref in activeCircuit) return bbPref
        }
        return null
    }

    private fun stripCycleAffix(node: String, cycle: Int): String {
        val mPre = unrolledPrefixRegex.matchEntire(node)
        if (mPre != null && mPre.groupValues[1].toIntOrNull() == cycle) {
            return mPre.groupValues[2]
        }
        val mCg = cgUnrollRegex.matchEntire(node)
        if (mCg != null && mCg.groupValues[2].toIntOrNull() == cycle) {
            return mCg.groupValues[1]
        }
        val suffix = "_$cycle"
        return if (node.endsWith(suffix) && node.length > suffix.length) {
            node.removeSuffix(suffix)
        } else {
            node
        }
    }

    private fun findSiblingCycleNode(
        node: String,
        currentCycle: Int,
        targetCycle: Int,
        circuit: Circuit
    ): String? {
        val mPre = unrolledPrefixRegex.matchEntire(node)
        if (mPre != null && mPre.groupValues[1].toIntOrNull() == currentCycle) {
            val candidate = "unrolled_${targetCycle}_${mPre.groupValues[2]}"
            if (candidate in circuit) return candidate
        }
        val mCg = cgUnrollRegex.matchEntire(node)
        if (mCg != null && mCg.groupValues[2].toIntOrNull() == currentCycle) {
            val candidate = "${mCg.groupValues[1]}_cg_unroll_$targetCycle"
            if (candidate in circuit) return candidate
        }
        val suffix = "_$currentCycle"
        if (node.endsWith(suffix) && node.length > suffix.length) {
            val candidate = "${node.removeSuffix(suffix)}_$targetCycle"
            if (candidate in circuit) return candidate
        }
        return null
    }
}

@Composable
fun CycleValueInspector(
    activeCircuit: Circuit,
    unrollSourceCircuit: Circuit?,
    unrollIoMap: Map<String, List<String>>,
    activeCycle: Int,
    cycleSnapshotValues: Map<String, LogicValue>,
    simulationValues: Map<String, LogicValue>,
    activeCycleNodes: Set<String>,
    selectedNode: String?,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onSelectNode: (String?) -> Unit,
    onToggleCycleInput: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val semantic = LocalCircuitColors.current
    val colorScheme = MaterialTheme.colorScheme

    var searchQuery by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf(WireInspectorFilter.ALL) }

    val allRows = remember(
        activeCircuit,
        unrollSourceCircuit,
        unrollIoMap,
        activeCycle,
        cycleSnapshotValues,
        simulationValues,
        activeCycleNodes
    ) {
        CycleInspectorExtractor.extractRows(
            activeCircuit = activeCircuit,
            unrollSourceCircuit = unrollSourceCircuit,
            unrollIoMap = unrollIoMap,
            activeCycle = activeCycle,
            cycleSnapshotValues = cycleSnapshotValues,
            simulationValues = simulationValues,
            activeCycleNodes = activeCycleNodes
        )
    }

    val filteredRows = remember(allRows, searchQuery, activeFilter) {
        val q = searchQuery.trim().lowercase()
        allRows.filter { row ->
            val matchesRole = when (activeFilter) {
                WireInspectorFilter.ALL -> true
                WireInspectorFilter.INPUTS -> row.category == WireCategory.INPUT
                WireInspectorFilter.FFS -> row.category == WireCategory.FF
                WireInspectorFilter.OUTPUTS -> row.category == WireCategory.OUTPUT
                WireInspectorFilter.INTERNAL -> row.category == WireCategory.INTERNAL
                WireInspectorFilter.CHANGED -> row.hasChanged
            }
            val matchesQuery = q.isEmpty() ||
                row.wireName.lowercase().contains(q) ||
                row.roleLabel.lowercase().contains(q) ||
                row.value.symbol.lowercase() == q
            matchesRole && matchesQuery
        }
    }

    val onesCount = remember(allRows) { allRows.count { it.value == LogicValue.ONE } }
    val zerosCount = remember(allRows) { allRows.count { it.value == LogicValue.ZERO } }
    val xCount = remember(allRows) { allRows.count { it.value == LogicValue.X } }

    Surface(
        color = colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = modifier
            .fillMaxSize()
            .testTag("cycle_value_inspector")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HorizontalDivider(color = colorScheme.outlineVariant)

            // Inspector Header + Binary Distribution Summary + Height Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.TableRows,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Wire Table",
                        style = MaterialTheme.typography.titleSmall,
                        color = colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Surface(
                        color = colorScheme.primaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "T=$activeCycle • ${allRows.size}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    BinaryCountBadge(label = "1", count = onesCount, color = semantic.logicOne)
                    BinaryCountBadge(label = "0", count = zerosCount, color = semantic.logicZero)
                    if (xCount > 0) {
                        BinaryCountBadge(label = "X", count = xCount, color = semantic.logicX)
                    }
                    IconButton(
                        onClick = onToggleExpand,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("cycle_inspector_expand_button")
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.UnfoldLess else Icons.Default.UnfoldMore,
                            contentDescription = if (expanded) "Collapse wire inspector" else "Expand wire inspector",
                            tint = colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Search + Category Filter Chips
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text("Filter wires by name, type, or 0/1/X", style = MaterialTheme.typography.bodySmall)
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear wire filter"
                                )
                            }
                        }
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cycle_wire_search")
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    WireInspectorFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = activeFilter == filter,
                            onClick = { activeFilter = filter },
                            enabled = filter != WireInspectorFilter.CHANGED || activeCycle > 0,
                            label = {
                                Text(filter.label, style = MaterialTheme.typography.labelSmall)
                            },
                            modifier = Modifier.testTag("cycle_filter_${filter.name.lowercase()}")
                        )
                    }
                }
            }

            // Table Header Row
            Surface(
                color = colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "WIRE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "TYPE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(62.dp)
                    )
                    if (activeCycle > 0) {
                        Text(
                            text = "T${activeCycle - 1}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(44.dp)
                        )
                    }
                    Text(
                        text = "T$activeCycle VAL",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.primary,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(64.dp)
                    )
                }
            }

            HorizontalDivider(color = colorScheme.outlineVariant)

            if (filteredRows.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (allRows.isEmpty()) {
                            "No wires found for cycle T=$activeCycle."
                        } else {
                            "No wires match current filter (${activeFilter.label})."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("cycle_wire_table")
                ) {
                    itemsIndexed(
                        items = filteredRows,
                        key = { _, row -> "${row.wireName}_${row.circuitNodeName}" }
                    ) { index, row ->
                        val isSelected = selectedNode == row.circuitNodeName
                        val rowBg = when {
                            isSelected -> colorScheme.primaryContainer.copy(alpha = 0.28f)
                            index % 2 == 1 -> colorScheme.surfaceVariant.copy(alpha = 0.22f)
                            else -> Color.Transparent
                        }

                        val roleColor = when (row.category) {
                            WireCategory.INPUT -> semantic.gateInput
                            WireCategory.FF -> semantic.gateBlackbox
                            WireCategory.OUTPUT -> colorScheme.tertiary
                            WireCategory.INTERNAL -> semantic.gateAnd
                        }

                        val valColor = logicColor(row.value, semantic)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .background(rowBg)
                                .clickable { onSelectNode(row.circuitNodeName) }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .testTag("cycle_wire_row_${row.wireName}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = row.wireName,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) colorScheme.primary else colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (row.hasChanged) {
                                        Surface(
                                            color = colorScheme.tertiaryContainer,
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = "Δ",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = colorScheme.onTertiaryContainer,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                                if (row.editableCycleNode != null) {
                                    Text(
                                        text = "Tap value to toggle",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 9.sp,
                                        color = colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier.width(62.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Surface(
                                    color = roleColor.copy(alpha = 0.14f),
                                    shape = RoundedCornerShape(5.dp)
                                ) {
                                    Text(
                                        text = row.roleLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = roleColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            if (activeCycle > 0) {
                                Row(
                                    modifier = Modifier.width(44.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    val prev = row.prevValue
                                    if (prev != null) {
                                        Text(
                                            text = prev.symbol,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = logicColor(prev, semantic).copy(alpha = 0.78f)
                                        )
                                        if (row.hasChanged) {
                                            Spacer(Modifier.width(2.dp))
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                                contentDescription = null,
                                                tint = colorScheme.tertiary,
                                                modifier = Modifier.size(11.dp)
                                            )
                                        }
                                    } else {
                                        Text(
                                            text = "—",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Box(
                                modifier = Modifier.width(64.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                val editableTarget = row.editableCycleNode
                                val badgeShape = RoundedCornerShape(7.dp)
                                Surface(
                                    color = valColor.copy(alpha = if (editableTarget != null) 0.24f else 0.14f),
                                    shape = badgeShape,
                                    border = if (editableTarget != null) {
                                        BorderStroke(1.dp, valColor.copy(alpha = 0.7f))
                                    } else {
                                        null
                                    },
                                    modifier = if (editableTarget != null) {
                                        Modifier
                                            .minimumInteractiveComponentSize()
                                            .clickable {
                                                onToggleCycleInput(editableTarget)
                                                onSelectNode(row.circuitNodeName)
                                            }
                                            .testTag("cycle_wire_toggle_${row.wireName}")
                                    } else {
                                        Modifier
                                    }
                                ) {
                                    Text(
                                        text = row.value.symbol,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = valColor,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.45f))
                    }
                }
            }
        }
    }
}

@Composable
private fun BinaryCountBadge(
    label: String,
    count: Int,
    color: Color
) {
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = "$label:$count",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

private fun logicColor(
    value: LogicValue,
    semantic: com.circuitgraph.app.ui.theme.CircuitSemanticColors
): Color = when (value) {
    LogicValue.ONE -> semantic.logicOne
    LogicValue.ZERO -> semantic.logicZero
    LogicValue.X -> semantic.logicX
}
