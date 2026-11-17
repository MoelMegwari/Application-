package com.circuitgraph.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.circuitgraph.app.core.Circuit
import com.circuitgraph.app.core.LogicValue
import com.circuitgraph.app.core.SatEngine
import com.circuitgraph.app.ui.theme.JetBrainsMonoFontFamily
import com.circuitgraph.app.ui.theme.LocalCircuitColors
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

enum class SignalRole(val badge: String) {
    INPUT("IN"),
    REGISTER_Q("FF.Q"),
    REGISTER_D("FF.D"),
    OUTPUT("OUT"),
    INTERNAL("INT")
}

data class WaveformTrack(
    val signalName: String,
    val role: SignalRole,
    val cycleNodeNames: List<String?>,
    val values: List<LogicValue>
) {
    val hasTransition: Boolean
        get() = values.zipWithNext().any { (a, b) -> a != b }
}

data class WaveformData(
    val cycleCount: Int,
    val tracks: List<WaveformTrack>
)

private enum class TrackFilter(val label: String) {
    IO_AND_FF("I/O & FFs"),
    ALL("All"),
    OUTPUTS("Outputs"),
    CHANGED("Transitions")
}

object WaveformExtractor {
    private val cgUnrollRegex = Regex("""^(.+)_cg_unroll_(\d+)$""")
    private val unrolledPrefixRegex = Regex("""^unrolled_(\d+)_(.+)$""")
    private val suffixCycleRegex = Regex("""^(.+)_(\d+)$""")

    fun detectIoMap(circuit: Circuit): Pair<Map<String, List<String>>, Int> {
        val nodes = circuit.orderedNodes()
        if (nodes.isEmpty()) return emptyMap<String, List<String>>() to 0

        // 1. Prefer explicit `_cg_unroll_<cycle>` pattern produced by CircuitTransforms.sequentialUnroll
        val cgGrouped = LinkedHashMap<String, MutableMap<Int, String>>()
        for (n in nodes) {
            val m = cgUnrollRegex.matchEntire(n) ?: continue
            val base = m.groupValues[1]
            val cyc = m.groupValues[2].toIntOrNull() ?: continue
            cgGrouped.getOrPut(base) { LinkedHashMap() }[cyc] = n
        }
        val maxCgCycles = cgGrouped.values.maxOfOrNull { (it.keys.maxOrNull() ?: -1) + 1 } ?: 0
        if (maxCgCycles >= 2) {
            val map = LinkedHashMap<String, List<String>>()
            for ((base, byCycle) in cgGrouped) {
                if (byCycle.size >= 2) {
                    map[base] = (0 until maxCgCycles).map { c -> byCycle[c] ?: "" }
                }
            }
            return map to maxCgCycles
        }

        // 2. Fallback for pre-unrolled Verilog netlists (e.g., s27_unrolled_4.v)
        val suffixGrouped = LinkedHashMap<String, MutableMap<Int, String>>()
        for (n in nodes) {
            val mPre = unrolledPrefixRegex.matchEntire(n)
            if (mPre != null) {
                val cyc = mPre.groupValues[1].toIntOrNull() ?: continue
                val base = mPre.groupValues[2]
                suffixGrouped.getOrPut(base) { LinkedHashMap() }[cyc] = n
                continue
            }
            val mSuf = suffixCycleRegex.matchEntire(n) ?: continue
            val base = mSuf.groupValues[1]
            val cyc = mSuf.groupValues[2].toIntOrNull() ?: continue
            if (cyc <= 32) {
                suffixGrouped.getOrPut(base) { LinkedHashMap() }.putIfAbsent(cyc, n)
            }
        }

        val multiCycleGroups = suffixGrouped.filterValues { byCycle ->
            0 in byCycle && byCycle.size >= 2
        }
        if (multiCycleGroups.isEmpty()) return emptyMap<String, List<String>>() to 0

        val totalCycles = multiCycleGroups.values.maxOf { (it.keys.maxOrNull() ?: 0) + 1 }
        val result = LinkedHashMap<String, List<String>>()
        for ((base, byCycle) in multiCycleGroups) {
            result[base] = (0 until totalCycles).map { c -> byCycle[c] ?: "" }
        }
        return result to totalCycles
    }

    fun buildWaveformData(
        circuit: Circuit,
        ioMap: Map<String, List<String>>,
        cycleCount: Int,
        simulationValues: Map<String, LogicValue>
    ): WaveformData? {
        if (cycleCount <= 0 || ioMap.isEmpty()) return null

        val tracks = mutableListOf<WaveformTrack>()
        val seenBases = HashSet<String>()

        for ((base, cycleNodes) in ioMap) {
            if (cycleNodes.none { it.isNotEmpty() && it in circuit }) continue
            seenBases.add(base)

            val nodeNames = (0 until cycleCount).map { idx ->
                cycleNodes.getOrNull(idx)?.takeIf { it.isNotEmpty() && it in circuit }
            }
            val firstValid = nodeNames.firstOrNull { it != null }
            val lastValid = nodeNames.lastOrNull { it != null }

            val role = when {
                firstValid != null && circuit.isOutput(firstValid) -> SignalRole.OUTPUT
                lastValid != null && circuit.isOutput(lastValid) -> SignalRole.OUTPUT
                base.endsWith("_Q", ignoreCase = true) || base.contains("_reg_Q", ignoreCase = true) -> SignalRole.REGISTER_Q
                base.endsWith("_D", ignoreCase = true) || base.contains("_reg_D", ignoreCase = true) -> SignalRole.REGISTER_D
                firstValid != null && circuit.type(firstValid) == "input" -> SignalRole.INPUT
                else -> SignalRole.INTERNAL
            }

            val vals = nodeNames.map { nodeName ->
                if (nodeName == null) LogicValue.X
                else simulationValues[nodeName] ?: LogicValue.X
            }

            tracks.add(
                WaveformTrack(
                    signalName = base,
                    role = role,
                    cycleNodeNames = nodeNames,
                    values = vals
                )
            )
        }

        // Also extract `unrolled_<cycle>_<node>` internal signals if not already in ioMap
        val internalByBase = LinkedHashMap<String, MutableMap<Int, String>>()
        for (n in circuit.orderedNodes()) {
            val m = unrolledPrefixRegex.matchEntire(n) ?: continue
            val cyc = m.groupValues[1].toIntOrNull() ?: continue
            val base = m.groupValues[2]
            if (cyc in 0 until cycleCount && base !in seenBases) {
                internalByBase.getOrPut(base) { LinkedHashMap() }[cyc] = n
            }
        }
        for ((base, byCyc) in internalByBase) {
            if (byCyc.size < 2) continue
            val nodeNames = (0 until cycleCount).map { idx -> byCyc[idx] }
            val vals = nodeNames.map { n ->
                if (n == null) LogicValue.X else simulationValues[n] ?: LogicValue.X
            }
            tracks.add(
                WaveformTrack(
                    signalName = base,
                    role = SignalRole.INTERNAL,
                    cycleNodeNames = nodeNames,
                    values = vals
                )
            )
        }

        val sorted = tracks.sortedWith(
            compareBy<WaveformTrack> {
                when (it.role) {
                    SignalRole.INPUT -> 0
                    SignalRole.REGISTER_Q -> 1
                    SignalRole.REGISTER_D -> 2
                    SignalRole.OUTPUT -> 3
                    SignalRole.INTERNAL -> 4
                }
            }.thenBy { it.signalName }
        )
        return WaveformData(cycleCount = cycleCount, tracks = sorted)
    }

    fun nodesForCycle(
        circuit: Circuit,
        ioMap: Map<String, List<String>>,
        cycle: Int
    ): Set<String> {
        val result = LinkedHashSet<String>()
        for (cycleNodes in ioMap.values) {
            val n = cycleNodes.getOrNull(cycle)
            if (!n.isNullOrEmpty() && n in circuit) {
                result.add(n)
            }
        }
        val prefix = "unrolled_${cycle}_"
        val suffixCg = "_cg_unroll_$cycle"
        val suffixIdx = "_$cycle"
        for (n in circuit.orderedNodes()) {
            if (n.startsWith(prefix) || n.endsWith(suffixCg) || n.endsWith(suffixIdx)) {
                result.add(n)
            }
        }
        return result
    }

    fun projectCycleValues(
        sourceCircuit: Circuit,
        ioMap: Map<String, List<String>>,
        cycle: Int,
        unrolledValues: Map<String, LogicValue>
    ): Map<String, LogicValue> {
        val known = LinkedHashMap<String, LogicValue>(sourceCircuit.size)
        val unrolledPrefix = "unrolled_${cycle}_"

        for (node in sourceCircuit.orderedNodes()) {
            val mappedFromIo = ioMap[node]?.getOrNull(cycle)
            val value = when {
                !mappedFromIo.isNullOrEmpty() && mappedFromIo in unrolledValues -> unrolledValues[mappedFromIo]
                "$unrolledPrefix$node" in unrolledValues -> unrolledValues["$unrolledPrefix$node"]
                '.' in node -> {
                    val bbKey = node.replace('.', '_')
                    val bbMapped = ioMap[bbKey]?.getOrNull(cycle)
                    when {
                        !bbMapped.isNullOrEmpty() && bbMapped in unrolledValues -> unrolledValues[bbMapped]
                        "$unrolledPrefix$bbKey" in unrolledValues -> unrolledValues["$unrolledPrefix$bbKey"]
                        else -> null
                    }
                }
                else -> null
            }
            if (value != null) {
                known[node] = value
            } else if (sourceCircuit.type(node) == "input" && node.equals("clk", ignoreCase = true)) {
                known[node] = LogicValue.ONE
            }
        }

        // Propagate any remaining unmapped internal/output nodes inside sourceCircuit cleanly
        return SatEngine.evaluate(sourceCircuit, known)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WaveformViewer(
    circuit: Circuit,
    waveformData: WaveformData?,
    activeCycle: Int,
    onCycleChanged: (Int) -> Unit,
    selectedNode: String?,
    onToggleCycleInput: (nodeName: String) -> Unit,
    onSelectNode: (String?) -> Unit,
    onAllZero: () -> Unit,
    onAlternatingStimulus: () -> Unit,
    onRandomizeInputs: () -> Unit,
    onUnrollSequential: (cycles: Int, initialFfValue: String?) -> Unit,
    onLoadSampleSequential: () -> Unit,
    onInsertPipelineAndUnroll: (cycles: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val semantic = LocalCircuitColors.current
    val colorScheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    if (waveformData == null || waveformData.tracks.isEmpty()) {
        WaveformEmptyOrUnrollPrompt(
            circuit = circuit,
            onUnrollSequential = onUnrollSequential,
            onLoadSampleSequential = onLoadSampleSequential,
            onInsertPipelineAndUnroll = onInsertPipelineAndUnroll,
            modifier = modifier
        )
        return
    }

    val maxCycleIndex = (waveformData.cycleCount - 1).coerceAtLeast(0)
    val cursorCycle = activeCycle.coerceIn(0, maxCycleIndex)

    var activeFilter by remember { mutableStateOf(TrackFilter.IO_AND_FF) }
    var isPlaying by remember(waveformData.cycleCount) { mutableStateOf(false) }
    var cycleWidthDp by remember { mutableFloatStateOf(64f) }
    var timelineViewportWidthPx by remember { mutableIntStateOf(0) }

    LaunchedEffect(isPlaying, waveformData.cycleCount, cursorCycle) {
        if (isPlaying && waveformData.cycleCount > 1) {
            delay(650L)
            onCycleChanged((cursorCycle + 1) % waveformData.cycleCount)
        }
    }

    val filteredTracks = remember(waveformData, activeFilter) {
        when (activeFilter) {
            TrackFilter.IO_AND_FF -> waveformData.tracks.filter { it.role != SignalRole.INTERNAL }
            TrackFilter.ALL -> waveformData.tracks
            TrackFilter.OUTPUTS -> waveformData.tracks.filter {
                it.role == SignalRole.OUTPUT || it.role == SignalRole.REGISTER_D
            }
            TrackFilter.CHANGED -> waveformData.tracks.filter { it.hasTransition }
        }
    }

    val labelColWidth = 128.dp
    val headerHeight = 46.dp
    val trackHeight = 46.dp
    val cycleWidth = cycleWidthDp.dp
    val cyclePx = with(density) { cycleWidth.toPx() }
    val hScrollState = rememberScrollState()
    val vScrollState = rememberScrollState()

    LaunchedEffect(cursorCycle, cyclePx, timelineViewportWidthPx) {
        if (timelineViewportWidthPx > 0) {
            val targetX = cursorCycle * cyclePx
            val currentStart = hScrollState.value.toFloat()
            val currentEnd = currentStart + timelineViewportWidthPx
            if (targetX < currentStart || targetX + cyclePx > currentEnd) {
                val scrollTarget = (targetX - timelineViewportWidthPx / 3f)
                    .toInt()
                    .coerceIn(0, hScrollState.maxValue)
                hScrollState.animateScrollTo(scrollTarget)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(semantic.canvasBackground)
            .testTag("waveform_viewer")
    ) {
        // Waveform Control Bar
        Surface(
            color = colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Waves,
                            contentDescription = null,
                            tint = colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "${waveformData.cycleCount} Cycles • T=$cursorCycle",
                            style = MaterialTheme.typography.titleSmall,
                            color = colorScheme.onSurface
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        IconButton(
                            onClick = { isPlaying = !isPlaying },
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("waveform_play_button")
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause clock sweep" else "Play clock sweep",
                                tint = colorScheme.primary
                            )
                        }
                        IconButton(
                            onClick = { cycleWidthDp = (cycleWidthDp - 12f).coerceAtLeast(42f) },
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("waveform_zoom_out")
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "Shrink cycle width")
                        }
                        IconButton(
                            onClick = { cycleWidthDp = (cycleWidthDp + 12f).coerceAtMost(120f) },
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("waveform_zoom_in")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Expand cycle width")
                        }
                    }
                }

                // Clock Cycle Scrubber Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        color = colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "T=$cursorCycle/$maxCycleIndex",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Slider(
                        value = cursorCycle.toFloat(),
                        onValueChange = { raw ->
                            isPlaying = false
                            val nextCycle = raw.roundToInt().coerceIn(0, maxCycleIndex)
                            if (nextCycle != cursorCycle) {
                                onCycleChanged(nextCycle)
                            }
                        },
                        enabled = waveformData.cycleCount > 1,
                        valueRange = 0f..maxCycleIndex.coerceAtLeast(1).toFloat(),
                        steps = (waveformData.cycleCount - 2).coerceAtLeast(0),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("waveform_cycle_slider")
                    )
                }

                // Stimulus & Track Filter Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TrackFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = activeFilter == filter,
                            onClick = { activeFilter = filter },
                            label = { Text(filter.label, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("waveform_filter_${filter.name.lowercase()}")
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = onAllZero,
                        modifier = Modifier.testTag("waveform_all_zero_button")
                    ) {
                        Text("0s", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = onAlternatingStimulus,
                        modifier = Modifier.testTag("waveform_toggle_stim_button")
                    ) {
                        Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("0/1 Pulse", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = onRandomizeInputs,
                        modifier = Modifier.testTag("waveform_random_stim_button")
                    ) {
                        Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Rand", style = MaterialTheme.typography.labelSmall)
                    }
                }

                Text(
                    text = "Scrub slider to update CircuitGraphCanvas • Tap IN / FF.Q0 cells to toggle 0 → 1 → X",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider(color = colorScheme.outlineVariant)

        // Main Split Waveform Table: Sticky Left Signal Labels + Scrollable Digital Traces
        val totalTimelineWidth = cycleWidth * waveformData.cycleCount
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(vScrollState)
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                // Sticky Left Signal Column
                Column(
                    modifier = Modifier
                        .width(labelColWidth)
                        .background(colorScheme.surface)
                ) {
                    // Clock Row Header
                    Box(
                        modifier = Modifier
                            .height(headerHeight)
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                color = colorScheme.primaryContainer,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "CLK",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                text = "Cycle",
                                style = MaterialTheme.typography.labelMedium,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    HorizontalDivider(color = colorScheme.outlineVariant)

                    filteredTracks.forEach { track ->
                        val cursorNode = track.cycleNodeNames.getOrNull(cursorCycle)
                        val cursorVal = track.values.getOrNull(cursorCycle) ?: LogicValue.X
                        val isTrackSelected = cursorNode != null && cursorNode == selectedNode
                        val roleColor = when (track.role) {
                            SignalRole.INPUT -> semantic.gateInput
                            SignalRole.REGISTER_Q, SignalRole.REGISTER_D -> semantic.gateBlackbox
                            SignalRole.OUTPUT -> colorScheme.tertiary
                            SignalRole.INTERNAL -> semantic.gateAnd
                        }
                        val valColor = when (cursorVal) {
                            LogicValue.ONE -> semantic.logicOne
                            LogicValue.ZERO -> semantic.logicZero
                            LogicValue.X -> semantic.logicX
                        }

                        Row(
                            modifier = Modifier
                                .height(trackHeight)
                                .fillMaxWidth()
                                .background(
                                    if (isTrackSelected) colorScheme.primaryContainer.copy(alpha = 0.25f)
                                    else Color.Transparent
                                )
                                .clickable {
                                    if (cursorNode != null) onSelectNode(cursorNode)
                                }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.signalName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = track.role.badge,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp,
                                    color = roleColor
                                )
                            }
                            Surface(
                                color = valColor.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(5.dp)
                            ) {
                                Text(
                                    text = cursorVal.symbol,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = valColor,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.55f))
                    }
                }

                // Right Scrollable Waveform Traces Canvas
                val headerPx = with(density) { headerHeight.toPx() }
                val trackPx = with(density) { trackHeight.toPx() }
                val dividerPx = with(density) { 1.dp.toPx() }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .onSizeChanged { timelineViewportWidthPx = it.width }
                        .horizontalScroll(hScrollState)
                ) {
                    val totalHeight = headerHeight + 1.dp + (trackHeight + 1.dp) * filteredTracks.size
                    Canvas(
                        modifier = Modifier
                            .width(totalTimelineWidth)
                            .height(totalHeight)
                            .testTag("waveform_canvas")
                            .pointerInput(filteredTracks, waveformData.cycleCount, cyclePx) {
                                detectTapGestures { offset ->
                                    val tappedCycle = (offset.x / cyclePx).toInt()
                                        .coerceIn(0, waveformData.cycleCount - 1)
                                    isPlaying = false
                                    onCycleChanged(tappedCycle)

                                    if (offset.y > headerPx) {
                                        val rowIdx = ((offset.y - headerPx) / (trackPx + dividerPx)).toInt()
                                        val track = filteredTracks.getOrNull(rowIdx)
                                        val nodeName = track?.cycleNodeNames?.getOrNull(tappedCycle)
                                        if (nodeName != null && nodeName in circuit) {
                                            if (circuit.type(nodeName) == "input") {
                                                onToggleCycleInput(nodeName)
                                            }
                                            onSelectNode(nodeName)
                                        }
                                    }
                                }
                            }
                    ) {
                        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)

                        // Active cursor column background highlight
                        val cursorX = cursorCycle * cyclePx
                        drawRect(
                            color = colorScheme.primary.copy(alpha = 0.08f),
                            topLeft = Offset(cursorX, 0f),
                            size = Size(cyclePx, size.height)
                        )

                        // Vertical cycle gridlines & headers
                        for (c in 0 until waveformData.cycleCount) {
                            val x = c * cyclePx
                            drawLine(
                                color = semantic.canvasGrid,
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = 1f,
                                pathEffect = dashEffect
                            )

                            // Cycle number label at top
                            val lbl = textMeasurer.measure(
                                text = "T$c",
                                style = TextStyle(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = if (c == cursorCycle) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 10.sp,
                                    color = if (c == cursorCycle) colorScheme.primary else colorScheme.onSurfaceVariant
                                )
                            )
                            drawText(
                                textLayoutResult = lbl,
                                topLeft = Offset(x + 6f, 4f)
                            )

                            // Draw synthetic CLK square wave in bottom half of header row
                            val clkHighY = 22f
                            val clkLowY = headerPx - 6f
                            val midX = x + cyclePx * 0.5f
                            val clkPath = Path().apply {
                                moveTo(x, clkLowY)
                                lineTo(x, clkHighY)
                                lineTo(midX, clkHighY)
                                lineTo(midX, clkLowY)
                                lineTo(x + cyclePx, clkLowY)
                            }
                            drawPath(
                                path = clkPath,
                                color = colorScheme.primary.copy(alpha = 0.8f),
                                style = Stroke(width = 2f)
                            )
                        }

                        drawLine(
                            color = colorScheme.outlineVariant,
                            start = Offset(0f, headerPx),
                            end = Offset(size.width, headerPx),
                            strokeWidth = dividerPx
                        )

                        // Draw each signal's digital waveform trace
                        filteredTracks.forEachIndexed { idx, track ->
                            val rowTop = headerPx + dividerPx + idx * (trackPx + dividerPx)
                            val rowBottom = rowTop + trackPx
                            val highY = rowTop + 10f
                            val lowY = rowBottom - 10f
                            val midY = (highY + lowY) / 2f

                            val editableRow = track.cycleNodeNames.any { it != null && it in circuit && circuit.type(it) == "input" }
                            if (editableRow && idx % 2 == 0) {
                                drawRect(
                                    color = colorScheme.surfaceVariant.copy(alpha = 0.16f),
                                    topLeft = Offset(0f, rowTop),
                                    size = Size(size.width, trackPx)
                                )
                            }

                            for (c in 0 until waveformData.cycleCount) {
                                val v = track.values[c]
                                val prev = if (c > 0) track.values[c - 1] else v
                                val x0 = c * cyclePx
                                val x1 = x0 + cyclePx
                                val xHalf = (x0 + x1) / 2f

                                val nodeAtCycle = track.cycleNodeNames.getOrNull(c)
                                val isEditableCell = nodeAtCycle != null && nodeAtCycle in circuit && circuit.type(nodeAtCycle) == "input"

                                when (v) {
                                    LogicValue.ONE -> {
                                        drawRect(
                                            color = semantic.logicOne.copy(alpha = 0.14f),
                                            topLeft = Offset(x0, highY),
                                            size = Size(cyclePx, lowY - highY)
                                        )
                                        drawLine(
                                            color = semantic.logicOne,
                                            start = Offset(x0, highY),
                                            end = Offset(x1, highY),
                                            strokeWidth = 2.6f,
                                            cap = StrokeCap.Round
                                        )
                                    }
                                    LogicValue.ZERO -> {
                                        drawLine(
                                            color = semantic.logicZero,
                                            start = Offset(x0, lowY),
                                            end = Offset(x1, lowY),
                                            strokeWidth = 2.4f,
                                            cap = StrokeCap.Round
                                        )
                                    }
                                    LogicValue.X -> {
                                        drawRect(
                                            color = semantic.logicX.copy(alpha = 0.14f),
                                            topLeft = Offset(x0, highY + 4f),
                                            size = Size(cyclePx, lowY - highY - 8f)
                                        )
                                        drawLine(
                                            color = semantic.logicX,
                                            start = Offset(x0, highY + 4f),
                                            end = Offset(x1, highY + 4f),
                                            strokeWidth = 1.8f
                                        )
                                        drawLine(
                                            color = semantic.logicX,
                                            start = Offset(x0, lowY - 4f),
                                            end = Offset(x1, lowY - 4f),
                                            strokeWidth = 1.8f
                                        )
                                    }
                                }

                                // Transition vertical edge between cycle c-1 and cycle c
                                if (c > 0 && prev != v) {
                                    val fromY = when (prev) {
                                        LogicValue.ONE -> highY
                                        LogicValue.ZERO -> lowY
                                        LogicValue.X -> midY
                                    }
                                    val toY = when (v) {
                                        LogicValue.ONE -> highY
                                        LogicValue.ZERO -> lowY
                                        LogicValue.X -> midY
                                    }
                                    val edgeColor = when {
                                        v == LogicValue.ONE -> semantic.logicOne
                                        v == LogicValue.X || prev == LogicValue.X -> semantic.logicX
                                        else -> semantic.logicZero
                                    }
                                    drawLine(
                                        color = edgeColor,
                                        start = Offset(x0, fromY),
                                        end = Offset(x0, toY),
                                        strokeWidth = 2.6f,
                                        cap = StrokeCap.Round
                                    )
                                }

                                // Cell logic symbol
                                if (cyclePx >= 40f) {
                                    val symColor = when (v) {
                                        LogicValue.ONE -> semantic.logicOne
                                        LogicValue.ZERO -> semantic.logicZero
                                        LogicValue.X -> semantic.logicX
                                    }
                                    val symLayout = textMeasurer.measure(
                                        text = v.symbol,
                                        style = TextStyle(
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = if (isEditableCell) FontWeight.Bold else FontWeight.Medium,
                                            fontSize = 9.5.sp,
                                            color = symColor.copy(alpha = if (isEditableCell) 0.95f else 0.72f)
                                        )
                                    )
                                    drawText(
                                        textLayoutResult = symLayout,
                                        topLeft = Offset(
                                            xHalf - symLayout.size.width / 2f,
                                            midY - symLayout.size.height / 2f
                                        )
                                    )
                                }
                            }

                            drawLine(
                                color = colorScheme.outlineVariant.copy(alpha = 0.45f),
                                start = Offset(0f, rowBottom),
                                end = Offset(size.width, rowBottom),
                                strokeWidth = dividerPx
                            )
                        }

                        // Vertical cursor line
                        drawLine(
                            color = colorScheme.primary,
                            start = Offset(cursorX, 0f),
                            end = Offset(cursorX, size.height),
                            strokeWidth = 2.5f
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WaveformEmptyOrUnrollPrompt(
    circuit: Circuit,
    onUnrollSequential: (cycles: Int, initialFfValue: String?) -> Unit,
    onLoadSampleSequential: () -> Unit,
    onInsertPipelineAndUnroll: (cycles: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var cycles by remember { mutableFloatStateOf(4f) }
    var initFf by remember { mutableStateOf<String?>("0") }
    val hasBlackboxes = circuit.blackboxes.isNotEmpty()

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Timeline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(44.dp)
                )
                Text(
                    text = if (hasBlackboxes) "Unroll '${circuit.name}' for Timing View" else "Sequential Timing Diagram",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = if (hasBlackboxes) {
                        "Detected ${circuit.blackboxes.size} sequential flip-flop(s). Unroll across clock cycles to inspect input, register, and output signal waveforms."
                    } else {
                        "'${circuit.name}' is combinational and has no clock cycles yet. Load the sequential s27 benchmark or insert pipeline flip-flops to visualize timing waves."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Clock Cycles: ${cycles.roundToInt()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Slider(
                        value = cycles,
                        onValueChange = { cycles = it },
                        valueRange = 2f..8f,
                        steps = 5
                    )
                }

                if (hasBlackboxes) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Initial Flip-Flop State (Cycle 0)", style = MaterialTheme.typography.labelSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("0" to "Reset (0)", "1" to "Set (1)", "x" to "Unknown (X)", null to "Free Inputs").forEach { (v, label) ->
                                FilterChip(
                                    selected = initFf == v,
                                    onClick = { initFf = v },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }

                    Button(
                        onClick = { onUnrollSequential(cycles.roundToInt(), initFf) },
                        modifier = Modifier.fillMaxWidth().testTag("waveform_unroll_now_button")
                    ) {
                        Icon(Icons.Default.Waves, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Unroll ${cycles.roundToInt()} Cycles & View Waveform")
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onLoadSampleSequential,
                            modifier = Modifier.weight(1f).testTag("waveform_load_s27_button")
                        ) {
                            Text("Load s27 (Seq)")
                        }
                        if (!circuit.isCyclic()) {
                            Button(
                                onClick = { onInsertPipelineAndUnroll(cycles.roundToInt()) },
                                modifier = Modifier.weight(1f).testTag("waveform_pipeline_unroll_button")
                            ) {
                                Text("Pipeline + Unroll")
                            }
                        }
                    }
                }
            }
        }
    }
}
