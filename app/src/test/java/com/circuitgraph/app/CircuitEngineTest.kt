package com.circuitgraph.app

import com.circuitgraph.app.core.Circuit
import com.circuitgraph.app.core.CircuitIo
import com.circuitgraph.app.core.CircuitProps
import com.circuitgraph.app.core.CircuitTransforms
import com.circuitgraph.app.core.CircuitUtils
import com.circuitgraph.app.core.LogicBuilders
import com.circuitgraph.app.core.LogicValue
import com.circuitgraph.app.core.SatEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CircuitEngineTest {

    @Test
    fun fullAdderTruthTableAndSat() {
        val fa = LogicBuilders.fullAdder()
        val eval = SatEngine.evaluateBoolean(
            fa,
            mapOf("x" to true, "y" to true, "cin" to false)
        )
        assertEquals(LogicValue.ZERO, eval["s"])
        assertEquals(LogicValue.ONE, eval["cout"])

        // Solve for s=1, cout=1 -> must require x=1, y=1, cin=1
        val sat = SatEngine.solve(fa, mapOf("s" to true, "cout" to true))
        assertTrue(sat.satisfiable)
        val model = sat.assignment!!
        assertTrue(model["x"] == true && model["y"] == true && model["cin"] == true)

        // Exact signal probability for XOR sum of 3 balanced inputs is 0.5
        val prob = CircuitProps.signalProbability(fa, "s")
        assertTrue(prob.isExact)
        assertEquals(0.5, prob.probability, 1e-6)
    }

    @Test
    fun verilogAndBenchRoundTripAndMiterEquivalence() {
        val mux = LogicBuilders.mux(4)
        val verilogText = CircuitIo.circuitToVerilog(mux, behavioral = true)
        val parsedVerilog = CircuitIo.verilogToCircuit(verilogText)

        val miter = CircuitTransforms.miter(mux, parsedVerilog)
        val diff = SatEngine.solve(miter, mapOf("sat" to true))
        assertFalse("Verilog round-trip must be equivalent", diff.satisfiable)

        val benchText = CircuitIo.circuitToBench(mux)
        val parsedBench = CircuitIo.benchToCircuit(benchText, name = mux.name)
        val benchMiter = CircuitTransforms.miter(mux, parsedBench)
        val benchDiff = SatEngine.solve(benchMiter, mapOf("sat" to true))
        assertFalse("Bench round-trip must be equivalent", benchDiff.satisfiable)
    }

    @Test
    fun aigConversionPreservesSemantics() {
        val c = LogicBuilders.adder(width = 2, carryIn = false, carryOut = true)
        val aig = CircuitTransforms.toAig(c)
        val allowed = setOf("input", "0", "1", "x", "and", "not")
        assertTrue(aig.orderedNodes().all { aig.type(it) in allowed })

        val miter = CircuitTransforms.miter(c, aig)
        val res = SatEngine.solve(miter, mapOf("sat" to true))
        assertFalse("AIG must be functionally equivalent to original adder", res.satisfiable)
    }

    @Test
    fun sequentialAndAcyclicUnrollAndLint() {
        val c = Circuit("seq_demo")
        c.add("clk", "input")
        c.add("x", "input")
        c.add("q", "buf", output = true)
        c.add("d", "xor", fanin = listOf("x", "q"))
        c.addBlackbox(CircuitIo.genericFlop, "ff0", mapOf("clk" to "clk", "d" to "d", "q" to "q"))

        val lint = CircuitUtils.lint(c)
        assertTrue("Expected clean lint report, got: ${lint.errors}", lint.isValid)

        val (unrolled, _) = CircuitTransforms.sequentialUnroll(c, 3, regDPort = "d", regQPort = "q")
        assertTrue(unrolled.blackboxes.isEmpty())
        assertFalse(unrolled.isCyclic())

        // Cyclic combinational circuit unrolling
        val cyclic = Circuit("cyc")
        cyclic.add("a", "input")
        cyclic.add("g", "and", fanin = listOf("a"), output = true)
        cyclic.connect("g", "g")
        assertTrue(cyclic.isCyclic())
        val acyc = CircuitTransforms.acyclicUnroll(cyclic)
        assertFalse(acyc.isCyclic())
    }

    @Test
    fun sensitizationAndUtils() {
        val c = LogicBuilders.halfAdder()
        val sensVec = CircuitProps.sensitize(c, "x")
        assertNotNull(sensVec)

        // Constant node cannot be sensitized to change outputs
        val c2 = Circuit("const_test")
        c2.add("a", "input")
        c2.add("k", "0")
        c2.add("y", "or", fanin = listOf("a", "k"), output = true)
        assertNotNull(CircuitProps.sensitize(c2, "a"))
        // If a=1, y is forced to 1 regardless of k
        assertNull(CircuitProps.sensitize(c2, "k", mapOf("a" to true)))

        assertEquals(3, CircuitUtils.clog2(8))
        assertEquals(listOf(true, false, true), CircuitUtils.intToBin(5, 3))
        assertEquals(5, CircuitUtils.binToInt(listOf(true, false, true)))
    }

    @Test
    fun waveformExtractionOverSequentialUnroll() {
        val c = Circuit("toggle_ff")
        c.add("clk", "input")
        c.add("en", "input")
        c.add("q", "buf", output = true)
        c.add("d", "xor", fanin = listOf("en", "q"))
        c.addBlackbox(CircuitIo.genericFlop, "ff0", mapOf("clk" to "clk", "d" to "d", "q" to "q"))

        val (unrolled, ioMap) = CircuitTransforms.sequentialUnroll(
            c,
            n = 4,
            regDPort = "d",
            regQPort = "q",
            initialValue = "0"
        )
        val simInputs = unrolled.startpoints().associateWith { LogicValue.ONE }
        val simVals = SatEngine.evaluate(unrolled, simInputs)
        val wf = com.circuitgraph.app.ui.components.WaveformExtractor.buildWaveformData(
            circuit = unrolled,
            ioMap = ioMap,
            cycleCount = 4,
            simulationValues = simVals
        )
        assertNotNull(wf)
        assertEquals(4, wf!!.cycleCount)
        val qTrack = wf.tracks.firstOrNull { it.signalName == "q" }
        assertNotNull(qTrack)
        // Toggle FF starting at 0 with en=1 alternates 0 -> 1 -> 0 -> 1
        assertEquals(
            listOf(LogicValue.ZERO, LogicValue.ONE, LogicValue.ZERO, LogicValue.ONE),
            qTrack!!.values
        )

        val (detectedMap, detectedCycles) = com.circuitgraph.app.ui.components.WaveformExtractor.detectIoMap(unrolled)
        assertEquals(4, detectedCycles)
        assertTrue("q" in detectedMap)

        val snapT0 = com.circuitgraph.app.ui.components.WaveformExtractor.projectCycleValues(c, ioMap, 0, simVals)
        val snapT1 = com.circuitgraph.app.ui.components.WaveformExtractor.projectCycleValues(c, ioMap, 1, simVals)
        assertEquals(LogicValue.ZERO, snapT0["q"])
        assertEquals(LogicValue.ONE, snapT1["q"])
        assertEquals(LogicValue.ONE, snapT0["d"])
        assertEquals(LogicValue.ZERO, snapT1["d"])

        val cycle2Nodes = com.circuitgraph.app.ui.components.WaveformExtractor.nodesForCycle(unrolled, ioMap, 2)
        assertTrue(cycle2Nodes.any { it.contains("2") })
    }

    @Test
    fun cycleValueInspectorExtractsWiresAndTransitions() {
        val c = Circuit("toggle_ff")
        c.add("clk", "input")
        c.add("en", "input")
        c.add("q", "buf", output = true)
        c.add("d", "xor", fanin = listOf("en", "q"))
        c.addBlackbox(CircuitIo.genericFlop, "ff0", mapOf("clk" to "clk", "d" to "d", "q" to "q"))

        val (unrolled, ioMap) = CircuitTransforms.sequentialUnroll(
            c,
            n = 3,
            regDPort = "d",
            regQPort = "q",
            initialValue = "0"
        )
        val simInputs = unrolled.startpoints().associateWith { LogicValue.ONE }
        val simVals = SatEngine.evaluate(unrolled, simInputs)
        val snapT0 = com.circuitgraph.app.ui.components.WaveformExtractor.projectCycleValues(c, ioMap, 0, simVals)
        val snapT1 = com.circuitgraph.app.ui.components.WaveformExtractor.projectCycleValues(c, ioMap, 1, simVals)

        val rowsT0 = com.circuitgraph.app.ui.components.CycleInspectorExtractor.extractRows(
            activeCircuit = unrolled,
            unrollSourceCircuit = c,
            unrollIoMap = ioMap,
            activeCycle = 0,
            cycleSnapshotValues = snapT0,
            simulationValues = simVals,
            activeCycleNodes = emptySet()
        )
        assertEquals(c.size, rowsT0.size)
        val qRow0 = rowsT0.first { it.wireName == "q" }
        val dRow0 = rowsT0.first { it.wireName == "d" }
        val enRow0 = rowsT0.first { it.wireName == "en" }
        assertEquals(LogicValue.ZERO, qRow0.value)
        assertEquals(LogicValue.ONE, dRow0.value)
        assertFalse(qRow0.hasChanged)
        assertNull(qRow0.prevValue)
        assertNotNull(enRow0.editableCycleNode)
        assertNull(qRow0.editableCycleNode)

        val rowsT1 = com.circuitgraph.app.ui.components.CycleInspectorExtractor.extractRows(
            activeCircuit = unrolled,
            unrollSourceCircuit = c,
            unrollIoMap = ioMap,
            activeCycle = 1,
            cycleSnapshotValues = snapT1,
            simulationValues = simVals,
            activeCycleNodes = emptySet()
        )
        val qRow1 = rowsT1.first { it.wireName == "q" }
        val dRow1 = rowsT1.first { it.wireName == "d" }
        assertEquals(LogicValue.ONE, qRow1.value)
        assertEquals(LogicValue.ZERO, qRow1.prevValue)
        assertTrue(qRow1.hasChanged)
        assertEquals(LogicValue.ZERO, dRow1.value)
        assertEquals(LogicValue.ONE, dRow1.prevValue)
        assertTrue(dRow1.hasChanged)

        // Pre-unrolled fallback (unrollSourceCircuit = null)
        val cycle1Nodes = com.circuitgraph.app.ui.components.WaveformExtractor.nodesForCycle(unrolled, ioMap, 1)
        val preUnrolledRows = com.circuitgraph.app.ui.components.CycleInspectorExtractor.extractRows(
            activeCircuit = unrolled,
            unrollSourceCircuit = null,
            unrollIoMap = ioMap,
            activeCycle = 1,
            cycleSnapshotValues = emptyMap(),
            simulationValues = simVals,
            activeCycleNodes = cycle1Nodes
        )
        assertTrue(preUnrolledRows.isNotEmpty())
        assertEquals(
            preUnrolledRows.map { it.circuitNodeName }.distinct().size,
            preUnrolledRows.size
        )
        val preQ = preUnrolledRows.firstOrNull { it.wireName == "q" }
        assertNotNull(preQ)
        assertEquals(LogicValue.ONE, preQ!!.value)
    }
}
