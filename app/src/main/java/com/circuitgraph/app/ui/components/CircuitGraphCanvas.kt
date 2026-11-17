package com.circuitgraph.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.circuitgraph.app.core.Circuit
import com.circuitgraph.app.core.LogicValue
import com.circuitgraph.app.ui.theme.JetBrainsMonoFontFamily
import com.circuitgraph.app.ui.theme.LocalCircuitColors
import kotlin.math.abs

private data class GraphNodePos(
    val id: String,
    val type: String,
    val isOutput: Boolean,
    val x: Float,
    val y: Float
)

private const val NODE_WIDTH = 136f
private const val NODE_HEIGHT = 54f
private const val COL_SPACING = 210f
private const val ROW_SPACING = 84f
private const val MAX_RENDER_NODES = 220

@Composable
fun CircuitGraphCanvas(
    circuit: Circuit,
    circuitVersion: Int,
    levels: Map<String, Int>,
    selectedNode: String?,
    simulationValues: Map<String, LogicValue>,
    onNodeSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
    highlightedNodes: Set<String> = emptySet(),
    cycleBadge: String? = null
) {
    val semantic = LocalCircuitColors.current
    val colorScheme = MaterialTheme.colorScheme
    val textMeasurer = rememberTextMeasurer()

    val layoutKey = remember(circuitVersion, circuit.name, circuit.size) {
        Triple(circuitVersion, circuit.name, circuit.size)
    }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember(layoutKey) { mutableFloatStateOf(1f) }
    var offset by remember(layoutKey) { mutableStateOf(Offset.Zero) }

    val layout = remember(layoutKey, levels) {
        computeDagLayout(circuit, levels)
    }

    val posMap = remember(layout) { layout.nodes.associateBy { it.id } }

    // Center & fit graph initially when canvasSize becomes available
    var initializedFit by remember(layoutKey) { mutableStateOf(false) }
    if (!initializedFit && canvasSize.width > 0 && layout.nodes.isNotEmpty()) {
        val minX = layout.nodes.minOf { it.x } - 40f
        val maxX = layout.nodes.maxOf { it.x } + NODE_WIDTH + 40f
        val minY = layout.nodes.minOf { it.y } - 40f
        val maxY = layout.nodes.maxOf { it.y } + NODE_HEIGHT + 40f
        val graphW = (maxX - minX).coerceAtLeast(1f)
        val graphH = (maxY - minY).coerceAtLeast(1f)
        val fitScale = minOf(
            canvasSize.width / graphW,
            canvasSize.height / graphH,
            1.25f
        ).coerceIn(0.3f, 1.5f)
        scale = fitScale
        offset = Offset(
            x = (canvasSize.width - graphW * fitScale) / 2f - minX * fitScale,
            y = (canvasSize.height - graphH * fitScale) / 2f - minY * fitScale
        )
        initializedFit = true
    }

    val highlightFanin = remember(layoutKey, selectedNode) {
        if (selectedNode != null && selectedNode in circuit) circuit.fanin(selectedNode) else emptySet()
    }
    val highlightFanout = remember(layoutKey, selectedNode) {
        if (selectedNode != null && selectedNode in circuit) circuit.fanout(selectedNode) else emptySet()
    }

    Box(
        modifier = modifier
            .background(semantic.canvasBackground)
            .clipToBounds()
            .onSizeChanged { canvasSize = it }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag("circuit_graph_canvas")
                .pointerInput(layoutKey) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val oldScale = scale
                        val newScale = (oldScale * zoom).coerceIn(0.2f, 3.0f)
                        val factor = newScale / oldScale
                        offset = centroid - (centroid - offset) * factor + pan
                        scale = newScale
                    }
                }
                .pointerInput(layoutKey, layout) {
                    detectTapGestures { tapOffset ->
                        val worldX = (tapOffset.x - offset.x) / scale
                        val worldY = (tapOffset.y - offset.y) / scale
                        val hit = layout.nodes.lastOrNull { node ->
                            worldX in node.x..(node.x + NODE_WIDTH) &&
                                worldY in node.y..(node.y + NODE_HEIGHT)
                        }
                        onNodeSelected(hit?.id)
                    }
                }
        ) {
            // Draw subtle blueprint grid
            val gridStep = 48f * scale
            if (gridStep >= 12f) {
                val startX = ((offset.x % gridStep) + gridStep) % gridStep
                val startY = ((offset.y % gridStep) + gridStep) % gridStep
                var gx = startX
                while (gx < size.width) {
                    drawLine(
                        color = semantic.canvasGrid,
                        start = Offset(gx, 0f),
                        end = Offset(gx, size.height),
                        strokeWidth = 1f
                    )
                    gx += gridStep
                }
                var gy = startY
                while (gy < size.height) {
                    drawLine(
                        color = semantic.canvasGrid,
                        start = Offset(0f, gy),
                        end = Offset(size.width, gy),
                        strokeWidth = 1f
                    )
                    gy += gridStep
                }
            }

            // Draw directed Bezier edges
            for ((u, v) in layout.edges) {
                val src = posMap[u] ?: continue
                val dst = posMap[v] ?: continue

                val p1 = Offset(
                    x = (src.x + NODE_WIDTH) * scale + offset.x,
                    y = (src.y + NODE_HEIGHT / 2f) * scale + offset.y
                )
                val p2 = Offset(
                    x = dst.x * scale + offset.x,
                    y = (dst.y + NODE_HEIGHT / 2f) * scale + offset.y
                )

                val hasCycleFilter = highlightedNodes.isNotEmpty()
                val isCycleEdge = !hasCycleFilter || (u in highlightedNodes && v in highlightedNodes)
                val isSelectedEdge = selectedNode != null && (u == selectedNode || v == selectedNode)
                val simVal = simulationValues[u]
                val baseEdgeColor = when {
                    isSelectedEdge -> colorScheme.primary
                    simVal == LogicValue.ONE -> semantic.logicOne.copy(alpha = 0.85f)
                    simVal == LogicValue.ZERO -> semantic.wireIdle
                    simVal == LogicValue.X -> semantic.logicX.copy(alpha = 0.65f)
                    else -> semantic.wireIdle
                }
                val edgeColor = if (isCycleEdge || isSelectedEdge) baseEdgeColor else baseEdgeColor.copy(alpha = 0.18f)
                val strokeW = (if (isSelectedEdge) 3.2f else if (hasCycleFilter && isCycleEdge) 2.5f else 1.9f) * scale.coerceIn(0.6f, 1.8f)

                val dx = abs(p2.x - p1.x) * 0.45f + 24f * scale
                val path = Path().apply {
                    moveTo(p1.x, p1.y)
                    cubicTo(
                        p1.x + dx, p1.y,
                        p2.x - dx, p2.y,
                        p2.x, p2.y
                    )
                }
                drawPath(path, color = edgeColor, style = Stroke(width = strokeW))

                // Arrowhead dot at target
                drawCircle(
                    color = edgeColor,
                    radius = (if (isSelectedEdge) 4.2f else 3f) * scale.coerceIn(0.6f, 1.5f),
                    center = p2
                )
            }

            // Draw gate nodes
            for (node in layout.nodes) {
                val sx = node.x * scale + offset.x
                val sy = node.y * scale + offset.y
                val sw = NODE_WIDTH * scale
                val sh = NODE_HEIGHT * scale

                // Cull off-screen nodes
                if (sx + sw < -20f || sx > size.width + 20f || sy + sh < -20f || sy > size.height + 20f) {
                    continue
                }

                val hasCycleFilter = highlightedNodes.isNotEmpty()
                val inActiveCycle = !hasCycleFilter || node.id in highlightedNodes
                val dimAlpha = if (inActiveCycle) 1f else 0.28f

                val isSelected = node.id == selectedNode
                val isNeighbor = node.id in highlightFanin || node.id in highlightFanout
                val gateAccent = gateColorFor(node.type, semantic)
                val simVal = simulationValues[node.id]

                val cardFill = if (isSelected) {
                    colorScheme.primaryContainer.copy(alpha = 0.92f)
                } else {
                    colorScheme.surface.copy(alpha = 0.95f * dimAlpha)
                }
                val borderColor = when {
                    isSelected -> colorScheme.primary
                    hasCycleFilter && inActiveCycle -> colorScheme.primary.copy(alpha = 0.85f)
                    isNeighbor -> colorScheme.primary.copy(alpha = 0.7f)
                    simVal == LogicValue.ONE -> semantic.logicOne.copy(alpha = dimAlpha)
                    node.isOutput -> colorScheme.tertiary.copy(alpha = dimAlpha)
                    else -> gateAccent.copy(alpha = 0.6f * dimAlpha)
                }

                // Node card
                drawRoundRect(
                    color = cardFill,
                    topLeft = Offset(sx, sy),
                    size = Size(sw, sh),
                    cornerRadius = CornerRadius(10f * scale, 10f * scale)
                )
                drawRoundRect(
                    color = borderColor,
                    topLeft = Offset(sx, sy),
                    size = Size(sw, sh),
                    cornerRadius = CornerRadius(10f * scale, 10f * scale),
                    style = Stroke(width = (if (isSelected) 3f else 1.7f) * scale.coerceIn(0.7f, 1.8f))
                )

                // Left type accent bar
                drawRoundRect(
                    color = gateAccent.copy(alpha = dimAlpha),
                    topLeft = Offset(sx, sy),
                    size = Size(7f * scale, sh),
                    cornerRadius = CornerRadius(4f * scale, 4f * scale)
                )

                // Text labels when zoomed in enough
                if (scale >= 0.42f) {
                    drawNodeText(
                        textMeasurer = textMeasurer,
                        node = node,
                        simVal = simVal,
                        sx = sx,
                        sy = sy,
                        sw = sw,
                        sh = sh,
                        scale = scale,
                        primaryTextColor = colorScheme.onSurface.copy(alpha = dimAlpha),
                        gateAccent = gateAccent.copy(alpha = dimAlpha),
                        logicOne = semantic.logicOne.copy(alpha = dimAlpha),
                        logicZero = semantic.logicZero.copy(alpha = dimAlpha),
                        logicX = semantic.logicX.copy(alpha = dimAlpha),
                        outputColor = colorScheme.tertiary.copy(alpha = dimAlpha)
                    )
                }
            }
        }

        // Cycle Snapshot or Truncated warning chip
        if (cycleBadge != null || layout.truncated) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (cycleBadge != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.94f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("canvas_cycle_badge")
                    ) {
                        Text(
                            text = cycleBadge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
                if (layout.truncated) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.92f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = "Showing first $MAX_RENDER_NODES of ${circuit.size} nodes for 60fps canvas",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        // Floating Zoom / Fit Controls
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            shape = RoundedCornerShape(14.dp),
            tonalElevation = 4.dp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                IconButton(
                    onClick = { scale = (scale * 0.8f).coerceAtLeast(0.2f) },
                    modifier = Modifier.size(40.dp).testTag("zoom_out_button")
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Zoom out", modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick = {
                        if (canvasSize.width > 0 && layout.nodes.isNotEmpty()) {
                            val minX = layout.nodes.minOf { it.x } - 40f
                            val maxX = layout.nodes.maxOf { it.x } + NODE_WIDTH + 40f
                            val minY = layout.nodes.minOf { it.y } - 40f
                            val maxY = layout.nodes.maxOf { it.y } + NODE_HEIGHT + 40f
                            val graphW = (maxX - minX).coerceAtLeast(1f)
                            val graphH = (maxY - minY).coerceAtLeast(1f)
                            val fitScale = minOf(canvasSize.width / graphW, canvasSize.height / graphH, 1.25f)
                                .coerceIn(0.25f, 1.5f)
                            scale = fitScale
                            offset = Offset(
                                x = (canvasSize.width - graphW * fitScale) / 2f - minX * fitScale,
                                y = (canvasSize.height - graphH * fitScale) / 2f - minY * fitScale
                            )
                        }
                    },
                    modifier = Modifier.size(40.dp).testTag("zoom_fit_button")
                ) {
                    Icon(Icons.Default.CenterFocusStrong, contentDescription = "Fit circuit", modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick = { scale = (scale * 1.25f).coerceAtMost(3.0f) },
                    modifier = Modifier.size(40.dp).testTag("zoom_in_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Zoom in", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNodeText(
    textMeasurer: TextMeasurer,
    node: GraphNodePos,
    simVal: LogicValue?,
    sx: Float,
    sy: Float,
    sw: Float,
    sh: Float,
    scale: Float,
    primaryTextColor: Color,
    gateAccent: Color,
    logicOne: Color,
    logicZero: Color,
    logicX: Color,
    outputColor: Color
) {
    val typeText = node.type.uppercase()
    val nameText = if (node.id.length > 11) node.id.take(10) + "…" else node.id

    val typeLayout = textMeasurer.measure(
        text = typeText,
        style = TextStyle(
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = (9.5f * scale.coerceIn(0.6f, 1.35f)).sp,
            color = gateAccent
        )
    )
    val nameLayout = textMeasurer.measure(
        text = nameText,
        style = TextStyle(
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = (11.5f * scale.coerceIn(0.6f, 1.35f)).sp,
            color = primaryTextColor
        )
    )

    drawText(
        textLayoutResult = typeLayout,
        topLeft = Offset(sx + 13f * scale, sy + 7f * scale)
    )
    drawText(
        textLayoutResult = nameLayout,
        topLeft = Offset(sx + 13f * scale, sy + 24f * scale)
    )

    if (node.isOutput) {
        val outLayout = textMeasurer.measure(
            text = "OUT",
            style = TextStyle(
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = (8.5f * scale.coerceIn(0.6f, 1.3f)).sp,
                color = outputColor
            )
        )
        drawText(
            textLayoutResult = outLayout,
            topLeft = Offset(sx + sw - outLayout.size.width - 8f * scale, sy + 7f * scale)
        )
    }

    if (simVal != null) {
        val valColor = when (simVal) {
            LogicValue.ONE -> logicOne
            LogicValue.ZERO -> logicZero
            LogicValue.X -> logicX
        }
        val badgeRadius = 9f * scale
        val bx = sx + sw - badgeRadius - 7f * scale
        val by = sy + sh - badgeRadius - 6f * scale
        drawCircle(color = valColor.copy(alpha = 0.22f), radius = badgeRadius, center = Offset(bx, by))
        val valLayout = textMeasurer.measure(
            text = simVal.symbol,
            style = TextStyle(
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = (9.5f * scale.coerceIn(0.6f, 1.3f)).sp,
                color = valColor
            )
        )
        drawText(
            textLayoutResult = valLayout,
            topLeft = Offset(bx - valLayout.size.width / 2f, by - valLayout.size.height / 2f)
        )
    }
}

private fun gateColorFor(type: String, semantic: com.circuitgraph.app.ui.theme.CircuitSemanticColors): Color {
    return when (type) {
        "input" -> semantic.gateInput
        "and", "nand" -> semantic.gateAnd
        "or", "nor" -> semantic.gateOr
        "xor", "xnor" -> semantic.gateXor
        "not", "buf" -> semantic.gateNot
        "bb_input", "bb_output" -> semantic.gateBlackbox
        else -> semantic.logicX
    }
}

private data class DagLayoutResult(
    val nodes: List<GraphNodePos>,
    val edges: List<Pair<String, String>>,
    val truncated: Boolean
)

private fun computeDagLayout(circuit: Circuit, precomputedLevels: Map<String, Int>): DagLayoutResult {
    val allNodes = circuit.orderedNodes()
    if (allNodes.isEmpty()) return DagLayoutResult(emptyList(), emptyList(), false)

    val truncated = allNodes.size > MAX_RENDER_NODES
    val renderList = if (truncated) allNodes.take(MAX_RENDER_NODES) else allNodes
    val renderSet = renderList.toHashSet()

    // Fallback levels if cyclic
    val levels = if (precomputedLevels.isNotEmpty()) {
        precomputedLevels
    } else {
        val fallback = LinkedHashMap<String, Int>()
        val inputs = renderList.filter { circuit.type(it) in listOf("input", "0", "1", "x", "bb_output") }
        val q = ArrayDeque<String>()
        for (i in inputs) {
            fallback[i] = 0
            q.addLast(i)
        }
        while (q.isNotEmpty()) {
            val u = q.removeFirst()
            val nextLvl = (fallback[u] ?: 0) + 1
            for (v in circuit.fanout(u)) {
                if (v in renderSet && v !in fallback) {
                    fallback[v] = nextLvl
                    q.addLast(v)
                }
            }
        }
        for (n in renderList) fallback.putIfAbsent(n, 0)
        fallback
    }

    val columns = LinkedHashMap<Int, MutableList<String>>()
    for (n in renderList) {
        val lvl = levels[n] ?: 0
        columns.getOrPut(lvl) { mutableListOf() }.add(n)
    }

    val sortedCols = columns.keys.sorted()
    val maxColHeight = columns.values.maxOfOrNull { it.size } ?: 1
    val totalHeight = maxColHeight * ROW_SPACING

    // Simple barycenter ordering pass for cleaner wires
    val yOrder = LinkedHashMap<String, Float>()
    for (col in sortedCols) {
        val list = columns[col]!!
        if (col > 0) {
            list.sortBy { n ->
                val fi = circuit.fanin(n).filter { it in yOrder }
                if (fi.isEmpty()) 0f else fi.map { yOrder[it]!! }.average().toFloat()
            }
        }
        val colH = list.size * ROW_SPACING
        val startY = (totalHeight - colH) / 2f
        list.forEachIndexed { idx, id ->
            yOrder[id] = startY + idx * ROW_SPACING
        }
    }

    val positioned = mutableListOf<GraphNodePos>()
    for (colIdx in sortedCols) {
        val x = colIdx * COL_SPACING
        for (id in columns[colIdx]!!) {
            positioned.add(
                GraphNodePos(
                    id = id,
                    type = circuit.type(id),
                    isOutput = circuit.isOutput(id),
                    x = x,
                    y = yOrder[id] ?: 0f
                )
            )
        }
    }

    val edges = circuit.edges().filter { it.first in renderSet && it.second in renderSet }
    return DagLayoutResult(positioned, edges, truncated)
}
