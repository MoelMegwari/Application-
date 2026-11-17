package com.circuitgraph.app.core

/**
 * Boolean and circuit property analysis functions, ported from `circuitgraph/props.py`.
 */
data class SignalProbResult(
    val node: String,
    val probability: Double,
    val isExact: Boolean,
    val startpointCount: Int
)

data class NodeSensitivityReport(
    val node: String,
    val signalProbability: Double,
    val isExactProbability: Boolean,
    val sensitizingInput: Map<String, Boolean>?,
    val influences: Map<String, Double>,
    val averageSensitivity: Double
)

object CircuitProps {

    fun levelize(c: Circuit): Map<String, Int> {
        if (c.isCyclic()) {
            throw IllegalArgumentException("Cannot levelize cyclic circuit")
        }
        val levels = LinkedHashMap<String, Int>()
        for (n in c.orderedNodes()) {
            if (c.type(n) in listOf("input", "0", "1", "x", "bb_output")) {
                levels[n] = 0
            }
        }
        for (n in c.topoSort()) {
            if (n in levels) continue
            val fi = c.fanin(n)
            levels[n] = if (fi.isEmpty()) 0 else (fi.maxOf { levels[it] ?: 0 } + 1)
        }
        return levels
    }

    fun signalProbability(c: Circuit, n: String): SignalProbResult {
        val coneNodes = c.transitiveFanin(n) + n
        val subc = CircuitTransforms.subcircuit(c, coneNodes)
        val mc = SatEngine.modelCount(subc, mapOf(n to true))
        return SignalProbResult(
            node = n,
            probability = mc.probability,
            isExact = mc.isExact,
            startpointCount = subc.startpoints().size
        )
    }

    fun sensitize(
        c: Circuit,
        n: String,
        assumptions: Map<String, Boolean> = emptyMap()
    ): Map<String, Boolean>? {
        val s = CircuitTransforms.sensitizationTransform(c, n)
        val combined = LinkedHashMap<String, Boolean>()
        combined["sat"] = true
        combined.putAll(assumptions)
        val res = SatEngine.solve(s, combined)
        if (!res.satisfiable || res.assignment == null) return null
        return s.startpoints().associateWith { res.assignment[it] ?: false }
    }

    fun influence(c: Circuit, n: String): Map<String, Double> {
        val sp = c.startpoints(n)
        if (sp.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, Double>()
        for (s in sp) {
            val sensCircuit = CircuitTransforms.sensitizationTransform(c, s, setOf(n))
            val mc = SatEngine.modelCount(sensCircuit, mapOf("sat" to true))
            result[s] = mc.probability
        }
        return result
    }

    fun avgSensitivity(c: Circuit, n: String): Double {
        return influence(c, n).values.sum()
    }

    fun analyzeNode(c: Circuit, n: String): NodeSensitivityReport {
        val sp = signalProbability(c, n)
        val sens = if (c.blackboxes.isEmpty() && !c.isCyclic() && c.endpoints(n).isNotEmpty()) {
            sensitize(c, n)
        } else null
        val infl = if (c.blackboxes.isEmpty() && !c.isCyclic() && c.startpoints(n).size <= 12 && n !in c.startpoints()) {
            influence(c, n)
        } else emptyMap()
        return NodeSensitivityReport(
            node = n,
            signalProbability = sp.probability,
            isExactProbability = sp.isExact,
            sensitizingInput = sens,
            influences = infl,
            averageSensitivity = infl.values.sum()
        )
    }
}
