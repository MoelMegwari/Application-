package com.circuitgraph.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.circuitgraph.app.core.BundledNetlistInfo
import com.circuitgraph.app.core.Circuit
import com.circuitgraph.app.core.CircuitIo
import com.circuitgraph.app.core.CircuitProps
import com.circuitgraph.app.core.CircuitRepository
import com.circuitgraph.app.core.CircuitTransforms
import com.circuitgraph.app.core.CircuitUtils
import com.circuitgraph.app.core.LintReport
import com.circuitgraph.app.core.LogicBuilders
import com.circuitgraph.app.core.LogicValue
import com.circuitgraph.app.core.ModelCountResult
import com.circuitgraph.app.core.NodeSensitivityReport
import com.circuitgraph.app.core.SatEngine
import com.circuitgraph.app.core.SatSolveResult
import com.circuitgraph.app.core.SavedCircuitDto
import com.circuitgraph.app.ui.components.WaveformData
import com.circuitgraph.app.ui.components.WaveformExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MiterVerificationResult(
    val comparedWith: String,
    val equivalent: Boolean,
    val counterexample: Map<String, Boolean>?,
    val solveTimeMs: Long
)

data class CircuitStats(
    val name: String,
    val nodeCount: Int,
    val edgeCount: Int,
    val inputCount: Int,
    val outputCount: Int,
    val blackboxCount: Int,
    val maxDepth: Int,
    val isCyclic: Boolean,
    val hasReconvergentFanout: Boolean,
    val gateHistogram: Map<String, Int>
)

enum class WorkbenchMode {
    GRAPH,
    WAVEFORM
}

data class CircuitUiState(
    val activeCircuit: Circuit = LogicBuilders.fullAdder(),
    val circuitVersion: Int = 0,
    val stats: CircuitStats? = null,
    val levels: Map<String, Int> = emptyMap(),
    val selectedNode: String? = null,
    val simulationInputs: Map<String, LogicValue> = emptyMap(),
    val simulationValues: Map<String, LogicValue> = emptyMap(),
    val isSimulating: Boolean = false,
    val unrollIoMap: Map<String, List<String>> = emptyMap(),
    val unrollCycleCount: Int = 0,
    val unrollSourceCircuit: Circuit? = null,
    val unrollSourceLevels: Map<String, Int> = emptyMap(),
    val activeCycleIndex: Int = 0,
    val activeCycleNodes: Set<String> = emptySet(),
    val cycleSnapshotValues: Map<String, LogicValue> = emptyMap(),
    val waveformData: WaveformData? = null,
    val workbenchMode: WorkbenchMode = WorkbenchMode.GRAPH,
    val satAssumptions: Map<String, Boolean> = emptyMap(),
    val satResult: SatSolveResult? = null,
    val modelCountResult: ModelCountResult? = null,
    val modelCountTargetNode: String? = null,
    val nodeReport: NodeSensitivityReport? = null,
    val lintReport: LintReport? = null,
    val miterResult: MiterVerificationResult? = null,
    val bundledNetlists: List<BundledNetlistInfo> = emptyList(),
    val savedCircuits: List<SavedCircuitDto> = emptyList(),
    val isBusy: Boolean = false,
    val statusBanner: String? = null,
    val errorBanner: String? = null,
    val canUndo: Boolean = false,
    val darkTheme: Boolean = true
)

class CircuitViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CircuitRepository(application)
    private val undoStack = ArrayDeque<Circuit>()

    private val _uiState = MutableStateFlow(CircuitUiState())
    val uiState: StateFlow<CircuitUiState> = _uiState.asStateFlow()

    init {
        refreshLibraries()
        loadInitialCircuit()
    }

    private fun loadInitialCircuit() {
        viewModelScope.launch {
            val bundled = withContext(Dispatchers.IO) { repository.listBundledNetlists() }
            val defaultCircuit = withContext(Dispatchers.IO) {
                try {
                    if (bundled.any { it.fileName == "c17.v" }) {
                        repository.loadBundledNetlist("c17.v")
                    } else {
                        LogicBuilders.fullAdder()
                    }
                } catch (_: Exception) {
                    LogicBuilders.fullAdder()
                }
            }
            setActiveCircuit(defaultCircuit, pushUndo = false, message = "Loaded ${defaultCircuit.name}")
        }
    }

    fun refreshLibraries() {
        viewModelScope.launch(Dispatchers.IO) {
            val bundled = repository.listBundledNetlists()
            val saved = repository.listSavedCircuits()
            _uiState.update { it.copy(bundledNetlists = bundled, savedCircuits = saved) }
        }
    }

    fun toggleTheme() {
        _uiState.update { it.copy(darkTheme = !it.darkTheme) }
    }

    fun dismissBanners() {
        _uiState.update { it.copy(statusBanner = null, errorBanner = null) }
    }

    fun setWorkbenchMode(mode: WorkbenchMode) {
        _uiState.update { it.copy(workbenchMode = mode) }
    }

    fun setActiveCycle(cycle: Int) {
        val state = _uiState.value
        val maxIdx = (state.unrollCycleCount - 1).coerceAtLeast(0)
        val clamped = cycle.coerceIn(0, maxIdx)
        val cycleNodes = if (state.unrollCycleCount > 0) {
            WaveformExtractor.nodesForCycle(state.activeCircuit, state.unrollIoMap, clamped)
        } else {
            emptySet()
        }
        val snapshot = if (state.unrollSourceCircuit != null && state.unrollCycleCount > 0) {
            WaveformExtractor.projectCycleValues(
                sourceCircuit = state.unrollSourceCircuit,
                ioMap = state.unrollIoMap,
                cycle = clamped,
                unrolledValues = state.simulationValues
            )
        } else {
            state.simulationValues
        }
        _uiState.update {
            it.copy(
                activeCycleIndex = clamped,
                activeCycleNodes = cycleNodes,
                cycleSnapshotValues = snapshot
            )
        }
    }

    fun setActiveCircuit(
        circuit: Circuit,
        pushUndo: Boolean = true,
        message: String? = null,
        unrollIoMap: Map<String, List<String>>? = null,
        unrollCycles: Int = 0,
        unrollSource: Circuit? = null,
        targetMode: WorkbenchMode? = null
    ) {
        if (pushUndo) {
            if (undoStack.size >= 15) undoStack.removeFirst()
            undoStack.addLast(_uiState.value.activeCircuit.copy())
        }
        viewModelScope.launch {
            val computed = withContext(Dispatchers.Default) {
                val stats = computeStats(circuit)
                val levels = if (!stats.isCyclic) CircuitProps.levelize(circuit) else emptyMap()
                val defaultInputs = circuit.startpoints().associateWith { LogicValue.ZERO }
                val simVals = SatEngine.evaluate(circuit, defaultInputs)
                val lint = CircuitUtils.lint(circuit)
                val (resolvedMap, resolvedCycles) = if (unrollIoMap != null && unrollCycles > 0) {
                    unrollIoMap to unrollCycles
                } else {
                    WaveformExtractor.detectIoMap(circuit)
                }
                val wf = WaveformExtractor.buildWaveformData(circuit, resolvedMap, resolvedCycles, simVals)
                val sourceLevels = if (unrollSource != null && !unrollSource.isCyclic()) {
                    CircuitProps.levelize(unrollSource)
                } else {
                    emptyMap()
                }
                val cycleNodes = if (resolvedCycles > 0) {
                    WaveformExtractor.nodesForCycle(circuit, resolvedMap, 0)
                } else {
                    emptySet()
                }
                val snapshot = if (unrollSource != null && resolvedCycles > 0) {
                    WaveformExtractor.projectCycleValues(unrollSource, resolvedMap, 0, simVals)
                } else {
                    simVals
                }
                CircuitSetupResult(
                    stats = stats,
                    levels = levels,
                    defaultInputs = defaultInputs,
                    simVals = simVals,
                    lint = lint,
                    ioMap = resolvedMap,
                    cycles = resolvedCycles,
                    waveform = wf,
                    sourceCircuit = unrollSource,
                    sourceLevels = sourceLevels,
                    cycleNodes = cycleNodes,
                    snapshot = snapshot
                )
            }
            _uiState.update { state ->
                state.copy(
                    activeCircuit = circuit,
                    circuitVersion = state.circuitVersion + 1,
                    stats = computed.stats,
                    levels = computed.levels,
                    selectedNode = null,
                    simulationInputs = computed.defaultInputs,
                    simulationValues = computed.simVals,
                    unrollIoMap = computed.ioMap,
                    unrollCycleCount = computed.cycles,
                    unrollSourceCircuit = computed.sourceCircuit,
                    unrollSourceLevels = computed.sourceLevels,
                    activeCycleIndex = 0,
                    activeCycleNodes = computed.cycleNodes,
                    cycleSnapshotValues = computed.snapshot,
                    waveformData = computed.waveform,
                    workbenchMode = targetMode ?: if (computed.waveform == null && state.workbenchMode == WorkbenchMode.WAVEFORM) {
                        WorkbenchMode.GRAPH
                    } else {
                        state.workbenchMode
                    },
                    satAssumptions = emptyMap(),
                    satResult = null,
                    modelCountResult = null,
                    modelCountTargetNode = null,
                    nodeReport = null,
                    lintReport = computed.lint,
                    miterResult = null,
                    canUndo = undoStack.isNotEmpty(),
                    statusBanner = message,
                    errorBanner = null
                )
            }
        }
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        setActiveCircuit(prev, pushUndo = false, message = "Reverted to ${prev.name}")
    }

    fun selectNode(node: String?) {
        _uiState.update { it.copy(selectedNode = node, nodeReport = null) }
    }

    private fun updateSimulationWithInputs(newInputs: Map<String, LogicValue>) {
        val state = _uiState.value
        val c = state.activeCircuit
        val newVals = SatEngine.evaluate(c, newInputs)
        val newWf = WaveformExtractor.buildWaveformData(c, state.unrollIoMap, state.unrollCycleCount, newVals)
        val snapshot = if (state.unrollSourceCircuit != null && state.unrollCycleCount > 0) {
            WaveformExtractor.projectCycleValues(
                sourceCircuit = state.unrollSourceCircuit,
                ioMap = state.unrollIoMap,
                cycle = state.activeCycleIndex,
                unrolledValues = newVals
            )
        } else {
            newVals
        }
        _uiState.update {
            it.copy(
                simulationInputs = newInputs,
                simulationValues = newVals,
                waveformData = newWf,
                cycleSnapshotValues = snapshot
            )
        }
    }

    fun cycleInputValue(node: String) {
        val current = _uiState.value.simulationInputs[node] ?: LogicValue.X
        val next = when (current) {
            LogicValue.ZERO -> LogicValue.ONE
            LogicValue.ONE -> LogicValue.X
            LogicValue.X -> LogicValue.ZERO
        }
        val newInputs = _uiState.value.simulationInputs.toMutableMap().apply { put(node, next) }
        updateSimulationWithInputs(newInputs)
    }

    fun setAllInputs(value: LogicValue) {
        val c = _uiState.value.activeCircuit
        val newInputs = c.startpoints().associateWith { value }
        updateSimulationWithInputs(newInputs)
    }

    fun applyAlternatingStimulus() {
        val state = _uiState.value
        val c = state.activeCircuit
        val newInputs = state.simulationInputs.toMutableMap()
        val ioMap = state.unrollIoMap
        if (ioMap.isNotEmpty() && state.unrollCycleCount > 0) {
            var sigIdx = 0
            for ((_, nodes) in ioMap) {
                for (cyc in nodes.indices) {
                    val n = nodes[cyc]
                    if (n.isNotEmpty() && n in newInputs) {
                        newInputs[n] = if ((cyc + sigIdx) % 2 == 0) LogicValue.ZERO else LogicValue.ONE
                    }
                }
                sigIdx++
            }
        } else {
            c.startpoints().forEachIndexed { idx, sp ->
                newInputs[sp] = if (idx % 2 == 0) LogicValue.ZERO else LogicValue.ONE
            }
        }
        updateSimulationWithInputs(newInputs)
    }

    fun randomizeInputs() {
        val c = _uiState.value.activeCircuit
        val newInputs = c.startpoints().associateWith {
            if (kotlin.random.Random.nextBoolean()) LogicValue.ONE else LogicValue.ZERO
        }
        updateSimulationWithInputs(newInputs)
    }

    fun addNode(
        name: String,
        type: String,
        fanin: List<String>,
        isOutput: Boolean
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(errorBanner = "Node name cannot be empty") }
            return
        }
        try {
            val updated = _uiState.value.activeCircuit.copy()
            updated.add(
                n = trimmed,
                nodeType = type,
                fanin = fanin,
                output = isOutput,
                addConnectedNodes = false
            )
            setActiveCircuit(updated, pushUndo = true, message = "Added $type node '$trimmed'")
        } catch (e: Exception) {
            _uiState.update { it.copy(errorBanner = e.message ?: "Failed to add node") }
        }
    }

    fun connectNodes(from: String, to: String) {
        try {
            val updated = _uiState.value.activeCircuit.copy()
            updated.connect(from, to)
            if (updated.isCyclic()) {
                // Allow cyclic graphs in CircuitGraph, just warn
                setActiveCircuit(updated, pushUndo = true, message = "Connected $from → $to (cycle detected)")
            } else {
                setActiveCircuit(updated, pushUndo = true, message = "Connected $from → $to")
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(errorBanner = e.message ?: "Connection failed") }
        }
    }

    fun disconnectNodes(from: String, to: String) {
        try {
            val updated = _uiState.value.activeCircuit.copy()
            updated.disconnect(from, to)
            setActiveCircuit(updated, pushUndo = true, message = "Disconnected $from → $to")
        } catch (e: Exception) {
            _uiState.update { it.copy(errorBanner = e.message ?: "Disconnect failed") }
        }
    }

    fun toggleNodeOutput(node: String) {
        try {
            val updated = _uiState.value.activeCircuit.copy()
            val next = !updated.isOutput(node)
            updated.setOutput(node, next)
            setActiveCircuit(updated, pushUndo = true, message = if (next) "Marked '$node' as output" else "Unmarked '$node' as output")
        } catch (e: Exception) {
            _uiState.update { it.copy(errorBanner = e.message) }
        }
    }

    fun removeNode(node: String) {
        val updated = _uiState.value.activeCircuit.copy()
        updated.remove(node)
        setActiveCircuit(updated, pushUndo = true, message = "Removed '$node'")
    }

    fun removeUnloaded() {
        val updated = _uiState.value.activeCircuit.copy()
        val removed = updated.removeUnloaded()
        setActiveCircuit(updated, pushUndo = true, message = "Removed ${removed.size} unloaded nodes")
    }

    fun createBlankCircuit(name: String) {
        val cleanName = name.trim().ifEmpty { "custom_circuit" }
        val c = Circuit(cleanName)
        c.add("in_0", "input")
        c.add("in_1", "input")
        c.add("out_0", "and", fanin = listOf("in_0", "in_1"), output = true)
        setActiveCircuit(c, pushUndo = true, message = "Created '$cleanName'")
    }

    fun loadBundled(fileName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorBanner = null) }
            try {
                val c = withContext(Dispatchers.IO) { repository.loadBundledNetlist(fileName) }
                _uiState.update { it.copy(isBusy = false) }
                setActiveCircuit(c, pushUndo = true, message = "Loaded $fileName (${c.size} nodes)")
            } catch (e: Exception) {
                _uiState.update { it.copy(isBusy = false, errorBanner = "Failed to load $fileName: ${e.message}") }
            }
        }
    }

    fun buildParametricLogic(builderType: String, width: Int, carryIn: Boolean, carryOut: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorBanner = null) }
            try {
                val c = withContext(Dispatchers.Default) {
                    when (builderType) {
                        "half_adder" -> LogicBuilders.halfAdder()
                        "full_adder" -> LogicBuilders.fullAdder()
                        "adder" -> LogicBuilders.adder(width, carryIn, carryOut)
                        "mux" -> LogicBuilders.mux(width)
                        "popcount" -> LogicBuilders.popcount(width)
                        else -> LogicBuilders.halfAdder()
                    }
                }
                _uiState.update { it.copy(isBusy = false) }
                setActiveCircuit(c, pushUndo = true, message = "Generated ${c.name} (${c.size} nodes)")
            } catch (e: Exception) {
                _uiState.update { it.copy(isBusy = false, errorBanner = e.message) }
            }
        }
    }

    fun importNetlistText(text: String, name: String? = null, formatHint: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorBanner = null) }
            try {
                val c = withContext(Dispatchers.Default) {
                    CircuitIo.fromText(text, name = name, formatHint = formatHint)
                }
                _uiState.update { it.copy(isBusy = false) }
                setActiveCircuit(c, pushUndo = true, message = "Parsed '${c.name}' (${c.size} nodes)")
            } catch (e: Exception) {
                _uiState.update { it.copy(isBusy = false, errorBanner = "Parse error: ${e.message}") }
            }
        }
    }

    fun saveCurrentCircuit() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dto = repository.saveCircuit(_uiState.value.activeCircuit)
                val saved = repository.listSavedCircuits()
                _uiState.update {
                    it.copy(savedCircuits = saved, statusBanner = "Saved '${dto.name}' locally")
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorBanner = "Save failed: ${e.message}") }
            }
        }
    }

    fun loadSavedCircuit(dto: SavedCircuitDto) {
        viewModelScope.launch {
            try {
                val c = withContext(Dispatchers.IO) { repository.loadSavedCircuit(dto) }
                setActiveCircuit(c, pushUndo = true, message = "Loaded saved circuit '${c.name}'")
            } catch (e: Exception) {
                _uiState.update { it.copy(errorBanner = "Load failed: ${e.message}") }
            }
        }
    }

    fun deleteSavedCircuit(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteSavedCircuit(id)
            val saved = repository.listSavedCircuits()
            _uiState.update { it.copy(savedCircuits = saved, statusBanner = "Deleted saved circuit") }
        }
    }

    // --- SAT & Analysis ---
    fun toggleSatAssumption(node: String, value: Boolean?) {
        val updated = _uiState.value.satAssumptions.toMutableMap()
        if (value == null) updated.remove(node) else updated[node] = value
        _uiState.update { it.copy(satAssumptions = updated) }
    }

    fun clearSatAssumptions() {
        _uiState.update { it.copy(satAssumptions = emptyMap(), satResult = null) }
    }

    fun runSatSolve() {
        val state = _uiState.value
        val c = state.activeCircuit
        if (c.blackboxes.isNotEmpty() && c.size > 400) {
            // Works on any circuit, just let user know
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorBanner = null) }
            try {
                val result = withContext(Dispatchers.Default) {
                    SatEngine.solve(c, state.satAssumptions)
                }
                // Also sync simulation view if SAT!
                val simVals = result.assignment?.mapValues {
                    if (it.value) LogicValue.ONE else LogicValue.ZERO
                } ?: state.simulationValues
                val simInps = if (result.assignment != null) {
                    c.startpoints().associateWith {
                        if (result.assignment[it] == true) LogicValue.ONE else LogicValue.ZERO
                    }
                } else state.simulationInputs

                val newWf = WaveformExtractor.buildWaveformData(c, state.unrollIoMap, state.unrollCycleCount, simVals)
                val newSnapshot = if (state.unrollSourceCircuit != null && state.unrollCycleCount > 0) {
                    WaveformExtractor.projectCycleValues(state.unrollSourceCircuit, state.unrollIoMap, state.activeCycleIndex, simVals)
                } else {
                    simVals
                }

                _uiState.update {
                    it.copy(
                        isBusy = false,
                        satResult = result,
                        simulationValues = simVals,
                        simulationInputs = simInps,
                        waveformData = newWf,
                        cycleSnapshotValues = newSnapshot,
                        statusBanner = if (result.satisfiable) {
                            "SAT in ${result.solveTimeMs} ms (${result.numVars} vars, ${result.numClauses} clauses)"
                        } else {
                            "UNSAT in ${result.solveTimeMs} ms"
                        }
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isBusy = false, errorBanner = "SAT error: ${e.message}") }
            }
        }
    }

    fun computeSignalProbability(node: String) {
        val c = _uiState.value.activeCircuit
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorBanner = null) }
            try {
                val res = withContext(Dispatchers.Default) {
                    val sp = CircuitProps.signalProbability(c, node)
                    ModelCountResult(
                        count = 0L,
                        isExact = sp.isExact,
                        totalInputSpace = Math.pow(2.0, sp.startpointCount.toDouble()),
                        probability = sp.probability
                    )
                }
                _uiState.update {
                    it.copy(isBusy = false, modelCountResult = res, modelCountTargetNode = node)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isBusy = false, errorBanner = "Model count error: ${e.message}") }
            }
        }
    }

    fun analyzeNode(node: String) {
        val c = _uiState.value.activeCircuit
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorBanner = null) }
            try {
                val report = withContext(Dispatchers.Default) {
                    CircuitProps.analyzeNode(c, node)
                }
                _uiState.update { it.copy(isBusy = false, nodeReport = report) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isBusy = false, errorBanner = "Analysis error: ${e.message}") }
            }
        }
    }

    fun runEquivalenceMiter(otherBundledFileName: String) {
        val c0 = _uiState.value.activeCircuit
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorBanner = null) }
            try {
                val res = withContext(Dispatchers.Default) {
                    val c1 = repository.loadBundledNetlist(otherBundledFileName)
                    val miter = CircuitTransforms.miter(c0, c1)
                    val sat = SatEngine.solve(miter, mapOf("sat" to true))
                    val cx = sat.assignment?.let { model ->
                        miter.startpoints().associateWith { model[it] ?: false }
                    }
                    MiterVerificationResult(
                        comparedWith = c1.name,
                        equivalent = !sat.satisfiable,
                        counterexample = cx,
                        solveTimeMs = sat.solveTimeMs
                    )
                }
                _uiState.update { it.copy(isBusy = false, miterResult = res) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isBusy = false, errorBanner = "Miter error: ${e.message}") }
            }
        }
    }

    // --- Transforms ---
    fun unrollSequentialForWaveform(cycles: Int, initialFfValue: String? = "0") {
        val c = _uiState.value.activeCircuit
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorBanner = null) }
            try {
                val (unrolled, ioMap) = withContext(Dispatchers.Default) {
                    CircuitTransforms.sequentialUnroll(c, cycles, initialValue = initialFfValue)
                }
                _uiState.update { it.copy(isBusy = false) }
                setActiveCircuit(
                    circuit = unrolled,
                    pushUndo = true,
                    message = "Unrolled ${c.name} for $cycles clock cycles",
                    unrollIoMap = ioMap,
                    unrollCycles = cycles,
                    unrollSource = c,
                    targetMode = WorkbenchMode.WAVEFORM
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(isBusy = false, errorBanner = "Unroll failed: ${e.message}") }
            }
        }
    }

    fun insertPipelineAndUnrollForWaveform(cycles: Int) {
        val c = _uiState.value.activeCircuit
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorBanner = null) }
            try {
                val (pipelined, pair) = withContext(Dispatchers.Default) {
                    val pipe = if (c.blackboxes.isEmpty()) CircuitTransforms.insertRegisters(c, 1) else c
                    pipe to CircuitTransforms.sequentialUnroll(pipe, cycles, initialValue = "0")
                }
                val (unrolled, ioMap) = pair
                _uiState.update { it.copy(isBusy = false) }
                setActiveCircuit(
                    circuit = unrolled,
                    pushUndo = true,
                    message = "Pipelined & unrolled ${c.name} for $cycles cycles",
                    unrollIoMap = ioMap,
                    unrollCycles = cycles,
                    unrollSource = pipelined,
                    targetMode = WorkbenchMode.WAVEFORM
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(isBusy = false, errorBanner = "Pipeline unroll failed: ${e.message}") }
            }
        }
    }

    fun applyTransform(transformId: String, param: Int = 2) {
        val c = _uiState.value.activeCircuit
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorBanner = null) }
            try {
                if (transformId == "seq_unroll") {
                    val (unrolled, ioMap) = withContext(Dispatchers.Default) {
                        CircuitTransforms.sequentialUnroll(c, param)
                    }
                    _uiState.update { it.copy(isBusy = false) }
                    setActiveCircuit(
                        circuit = unrolled,
                        pushUndo = true,
                        message = "Unrolled ${c.name} for $param cycles",
                        unrollIoMap = ioMap,
                        unrollCycles = param,
                        unrollSource = c,
                        targetMode = WorkbenchMode.WAVEFORM
                    )
                    return@launch
                }

                val result = withContext(Dispatchers.Default) {
                    when (transformId) {
                        "limit_fanin" -> CircuitTransforms.limitFanin(c, param)
                        "limit_fanout" -> CircuitTransforms.limitFanout(c, param)
                        "to_aig" -> CircuitTransforms.toAig(c)
                        "strip_blackboxes" -> CircuitTransforms.stripBlackboxes(c)
                        "acyclic_unroll" -> CircuitTransforms.acyclicUnroll(c)
                        "ternary" -> CircuitTransforms.ternary(c).first
                        "insert_regs" -> CircuitTransforms.insertRegisters(c, param)
                        "remove_unloaded" -> c.copy().apply { removeUnloaded() }
                        else -> c.copy()
                    }
                }
                _uiState.update { it.copy(isBusy = false) }
                setActiveCircuit(result, pushUndo = true, message = "Applied $transformId → ${result.name} (${result.size} nodes)")
            } catch (e: Exception) {
                _uiState.update { it.copy(isBusy = false, errorBanner = "Transform failed: ${e.message}") }
            }
        }
    }

    private fun computeStats(c: Circuit): CircuitStats {
        val cyclic = c.isCyclic()
        val levels = if (!cyclic) CircuitProps.levelize(c) else emptyMap()
        val histogram = LinkedHashMap<String, Int>()
        for (n in c.orderedNodes()) {
            val t = c.type(n)
            histogram[t] = (histogram[t] ?: 0) + 1
        }
        return CircuitStats(
            name = c.name,
            nodeCount = c.size,
            edgeCount = c.edges().size,
            inputCount = c.inputs().size,
            outputCount = c.outputs().size,
            blackboxCount = c.blackboxes.size,
            maxDepth = levels.values.maxOrNull() ?: 0,
            isCyclic = cyclic,
            hasReconvergentFanout = if (c.size <= 300) c.hasReconvergentFanout() else false,
            gateHistogram = histogram
        )
    }

    private data class CircuitSetupResult(
        val stats: CircuitStats,
        val levels: Map<String, Int>,
        val defaultInputs: Map<String, LogicValue>,
        val simVals: Map<String, LogicValue>,
        val lint: LintReport,
        val ioMap: Map<String, List<String>>,
        val cycles: Int,
        val waveform: WaveformData?,
        val sourceCircuit: Circuit?,
        val sourceLevels: Map<String, Int>,
        val cycleNodes: Set<String>,
        val snapshot: Map<String, LogicValue>
    )
}
