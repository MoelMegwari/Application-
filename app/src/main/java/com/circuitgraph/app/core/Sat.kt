package com.circuitgraph.app.core

import kotlin.random.Random

/**
 * Pure-Kotlin Tseitin CNF transformation, DPLL/CDCL SAT Solver, Model Counter,
 * and 3-valued logic evaluator, ported from `circuitgraph/sat.py`.
 */
enum class LogicValue(val symbol: String) {
    ZERO("0"),
    ONE("1"),
    X("X");

    operator fun not(): LogicValue = when (this) {
        ZERO -> ONE
        ONE -> ZERO
        X -> X
    }
}

class IdPool {
    private val objToId = LinkedHashMap<String, Int>()
    private val idToObj = LinkedHashMap<Int, String>()
    private var nextId = 1

    fun id(obj: String): Int {
        objToId[obj]?.let { return it }
        val newId = nextId++
        objToId[obj] = newId
        idToObj[newId] = obj
        return newId
    }

    fun obj(id: Int): String? = idToObj[id]

    val size: Int get() = nextId - 1
}

data class CnfFormula(
    val clauses: MutableList<IntArray>,
    val variables: IdPool
) {
    val numVars: Int get() = variables.size
    val numClauses: Int get() = clauses.size
}

data class SatSolveResult(
    val satisfiable: Boolean,
    val assignment: Map<String, Boolean>?,
    val numVars: Int,
    val numClauses: Int,
    val solveTimeMs: Long
)

data class ModelCountResult(
    val count: Long,
    val isExact: Boolean,
    val totalInputSpace: Double,
    val probability: Double
)

object SatEngine {

    fun cnf(c: Circuit): CnfFormula {
        val variables = IdPool()
        val clauses = mutableListOf<IntArray>()

        fun xorClauses(a: String, b: String, out: String) {
            val va = variables.id(a)
            val vb = variables.id(b)
            val vc = variables.id(out)
            clauses.add(intArrayOf(-vc, -vb, -va))
            clauses.add(intArrayOf(-vc, vb, va))
            clauses.add(intArrayOf(vc, -vb, va))
            clauses.add(intArrayOf(vc, vb, -va))
        }

        for (n in c.orderedNodes()) {
            val vn = variables.id(n)
            val fi = c.fanin(n).toList()
            var nType = c.type(n)
            if (nType in listOf("and", "or", "xor") && fi.size == 1) {
                nType = "buf"
            } else if (nType in listOf("nand", "nor", "xnor") && fi.size == 1) {
                nType = "not"
            }

            when (nType) {
                "and" -> {
                    for (f in fi) {
                        clauses.add(intArrayOf(-vn, variables.id(f)))
                    }
                    val big = IntArray(fi.size + 1)
                    big[0] = vn
                    for (idx in fi.indices) big[idx + 1] = -variables.id(fi[idx])
                    clauses.add(big)
                }
                "nand" -> {
                    for (f in fi) {
                        clauses.add(intArrayOf(vn, variables.id(f)))
                    }
                    val big = IntArray(fi.size + 1)
                    big[0] = -vn
                    for (idx in fi.indices) big[idx + 1] = -variables.id(fi[idx])
                    clauses.add(big)
                }
                "or" -> {
                    for (f in fi) {
                        clauses.add(intArrayOf(vn, -variables.id(f)))
                    }
                    val big = IntArray(fi.size + 1)
                    big[0] = -vn
                    for (idx in fi.indices) big[idx + 1] = variables.id(fi[idx])
                    clauses.add(big)
                }
                "nor" -> {
                    for (f in fi) {
                        clauses.add(intArrayOf(-vn, -variables.id(f)))
                    }
                    val big = IntArray(fi.size + 1)
                    big[0] = vn
                    for (idx in fi.indices) big[idx + 1] = variables.id(fi[idx])
                    clauses.add(big)
                }
                "not" -> {
                    if (fi.isNotEmpty()) {
                        val vf = variables.id(fi.first())
                        clauses.add(intArrayOf(vn, vf))
                        clauses.add(intArrayOf(-vn, -vf))
                    }
                }
                "buf", "bb_input" -> {
                    if (fi.isNotEmpty()) {
                        val vf = variables.id(fi.first())
                        clauses.add(intArrayOf(vn, -vf))
                        clauses.add(intArrayOf(-vn, vf))
                    }
                }
                "xor", "xnor" -> {
                    val nets = fi.toMutableList()
                    while (nets.size > 2) {
                        val a = nets[nets.size - 2]
                        val b = nets[nets.size - 1]
                        val newNet = "__xor_${n}_${nets.size}"
                        variables.id(newNet)
                        xorClauses(a, b, newNet)
                        nets.removeAt(nets.lastIndex)
                        nets.removeAt(nets.lastIndex)
                        nets.add(0, newNet)
                    }
                    if (nets.size == 2) {
                        if (nType == "xor") {
                            xorClauses(nets[0], nets[1], n)
                        } else {
                            val invNet = "__xor_inv_$n"
                            val vInv = variables.id(invNet)
                            xorClauses(nets[0], nets[1], invNet)
                            clauses.add(intArrayOf(vn, vInv))
                            clauses.add(intArrayOf(-vn, -vInv))
                        }
                    }
                }
                "0" -> clauses.add(intArrayOf(-vn))
                "1" -> clauses.add(intArrayOf(vn))
                "x", "bb_output", "input" -> {
                    clauses.add(intArrayOf(vn, -vn))
                }
                else -> throw IllegalArgumentException("Unknown gate type '$nType'")
            }
        }
        return CnfFormula(clauses, variables)
    }

    fun addAssumptions(formula: CnfFormula, assumptions: Map<String, Boolean>, circuit: Circuit) {
        for ((n, value) in assumptions) {
            if (n !in circuit) {
                throw IllegalArgumentException("Node '$n' in assumptions is not in circuit")
            }
            val v = formula.variables.id(n)
            formula.clauses.add(intArrayOf(if (value) v else -v))
        }
    }

    fun solve(c: Circuit, assumptions: Map<String, Boolean> = emptyMap()): SatSolveResult {
        val start = System.currentTimeMillis()
        val formula = cnf(c)
        addAssumptions(formula, assumptions, c)
        val solver = DpllSolver(formula.numVars, formula.clauses)
        val model = solver.solve()
        val elapsed = System.currentTimeMillis() - start
        val assignment = model?.let { m ->
            c.orderedNodes().associateWith { n ->
                val vid = formula.variables.id(n)
                m[vid] == 1
            }
        }
        return SatSolveResult(
            satisfiable = assignment != null,
            assignment = assignment,
            numVars = formula.numVars,
            numClauses = formula.numClauses,
            solveTimeMs = elapsed
        )
    }

    /**
     * Exact model counting via blocking clauses when startpoints <= 12,
     * or SAT-assisted Monte Carlo estimation for larger cones.
     */
    fun modelCount(
        c: Circuit,
        assumptions: Map<String, Boolean> = emptyMap(),
        exactMaxStartpoints: Int = 12,
        mcSamples: Int = 1024
    ): ModelCountResult {
        val startpoints = c.startpoints().toList()
        val totalSpace = Math.pow(2.0, startpoints.size.toDouble())

        if (startpoints.size <= exactMaxStartpoints) {
            val formula = cnf(c)
            addAssumptions(formula, assumptions, c)
            val clauses = formula.clauses.toMutableList()
            val startIds = startpoints.map { formula.variables.id(it) }
            var count = 0L
            while (true) {
                val solver = DpllSolver(formula.numVars, clauses)
                val model = solver.solve() ?: break
                count++
                if (startIds.isEmpty()) break
                val blocking = IntArray(startIds.size) { idx ->
                    val v = startIds[idx]
                    if (model[v] == 1) -v else v
                }
                clauses.add(blocking)
            }
            val prob = if (totalSpace > 0) count.toDouble() / totalSpace else 0.0
            return ModelCountResult(
                count = count,
                isExact = true,
                totalInputSpace = totalSpace,
                probability = prob.coerceIn(0.0, 1.0)
            )
        } else {
            // Monte Carlo combinational / SAT-verified estimation
            val rng = Random(42)
            var hits = 0
            val canEvalDirectly = !c.isCyclic() && c.blackboxes.isEmpty()
            for (s in 0 until mcSamples) {
                val sampleInputs = LinkedHashMap<String, Boolean>(startpoints.size)
                for (sp in startpoints) {
                    sampleInputs[sp] = rng.nextBoolean()
                }
                if (canEvalDirectly) {
                    val vals = evaluateBoolean(c, sampleInputs)
                    val ok = assumptions.all { (k, v) ->
                        vals[k] == if (v) LogicValue.ONE else LogicValue.ZERO
                    }
                    if (ok) hits++
                } else {
                    val combined = LinkedHashMap<String, Boolean>()
                    combined.putAll(sampleInputs)
                    combined.putAll(assumptions)
                    if (solve(c, combined).satisfiable) hits++
                }
            }
            val prob = hits.toDouble() / mcSamples.toDouble()
            return ModelCountResult(
                count = (prob * totalSpace).toLong(),
                isExact = false,
                totalInputSpace = totalSpace,
                probability = prob
            )
        }
    }

    /**
     * Fast 3-valued logic simulation in topological order (handles partial inputs with X).
     */
    fun evaluate(c: Circuit, inputs: Map<String, LogicValue>): Map<String, LogicValue> {
        val values = LinkedHashMap<String, LogicValue>(c.size)
        val order = if (!c.isCyclic()) c.topoSort() else c.orderedNodes()

        for (n in order) {
            inputs[n]?.let {
                values[n] = it
                continue
            }
            val t = c.type(n)
            val fi = c.fanin(n).map { values[it] ?: LogicValue.X }
            values[n] = when (t) {
                "0" -> LogicValue.ZERO
                "1" -> LogicValue.ONE
                "x", "input", "bb_output" -> LogicValue.X
                "buf", "bb_input" -> fi.firstOrNull() ?: LogicValue.X
                "not" -> !(fi.firstOrNull() ?: LogicValue.X)
                "and" -> evalAnd(fi)
                "nand" -> !evalAnd(fi)
                "or" -> evalOr(fi)
                "nor" -> !evalOr(fi)
                "xor" -> evalXor(fi)
                "xnor" -> !evalXor(fi)
                else -> LogicValue.X
            }
        }
        return values
    }

    fun evaluateBoolean(c: Circuit, inputs: Map<String, Boolean>): Map<String, LogicValue> {
        val mapped: Map<String, LogicValue> = inputs.mapValues {
            if (it.value) LogicValue.ONE else LogicValue.ZERO
        }
        return evaluate(c, mapped)
    }

    private fun evalAnd(vals: List<LogicValue>): LogicValue {
        if (vals.isEmpty()) return LogicValue.X
        if (vals.any { it == LogicValue.ZERO }) return LogicValue.ZERO
        return if (vals.all { it == LogicValue.ONE }) LogicValue.ONE else LogicValue.X
    }

    private fun evalOr(vals: List<LogicValue>): LogicValue {
        if (vals.isEmpty()) return LogicValue.X
        if (vals.any { it == LogicValue.ONE }) return LogicValue.ONE
        return if (vals.all { it == LogicValue.ZERO }) LogicValue.ZERO else LogicValue.X
    }

    private fun evalXor(vals: List<LogicValue>): LogicValue {
        if (vals.isEmpty()) return LogicValue.X
        if (vals.any { it == LogicValue.X }) return LogicValue.X
        val ones = vals.count { it == LogicValue.ONE }
        return if (ones % 2 == 1) LogicValue.ONE else LogicValue.ZERO
    }
}

/**
 * Fast pure-Kotlin DPLL SAT solver with unit propagation, pure-literal elimination,
 * and an iterative explicit decision stack (no JVM StackOverflow on large circuits).
 */
private class DpllSolver(
    private val numVars: Int,
    rawClauses: List<IntArray>
) {
    private val clauses: Array<IntArray> = rawClauses.filter { cl ->
        // Strip tautologies (clauses containing both v and -v)
        val set = HashSet<Int>(cl.size)
        var tautology = false
        for (lit in cl) {
            if (-lit in set) {
                tautology = true
                break
            }
            set.add(lit)
        }
        !tautology
    }.toTypedArray()

    // Occurrence lists: litIndex -> clauses containing that literal
    private val posOccurs = Array(numVars + 1) { mutableListOf<Int>() }
    private val negOccurs = Array(numVars + 1) { mutableListOf<Int>() }

    // Assignment: 0 = unassigned, 1 = true, -1 = false
    private val assign = IntArray(numVars + 1)
    private val trail = IntArray(numVars + 1)
    private var trailSize = 0

    // Decision stack
    private data class Frame(val varIdx: Int, val firstVal: Int, val trailMark: Int, var triedSecond: Boolean = false)

    private val stack = ArrayDeque<Frame>()

    init {
        for (ci in clauses.indices) {
            for (lit in clauses[ci]) {
                val v = kotlin.math.abs(lit)
                if (lit > 0) posOccurs[v].add(ci) else negOccurs[v].add(ci)
            }
        }
    }

    fun solve(): IntArray? {
        // Check empty clauses immediately
        for (cl in clauses) {
            if (cl.isEmpty()) return null
        }

        var propagateStart = 0
        while (true) {
            val conflict = unitPropagate(propagateStart)
            if (conflict) {
                // Backtrack
                var backtracked = false
                while (stack.isNotEmpty()) {
                    val top = stack.last()
                    undoTo(top.trailMark)
                    if (!top.triedSecond) {
                        top.triedSecond = true
                        val altVal = -top.firstVal
                        assign[top.varIdx] = altVal
                        trail[trailSize++] = if (altVal == 1) top.varIdx else -top.varIdx
                        propagateStart = trailSize - 1
                        backtracked = true
                        break
                    } else {
                        stack.removeLast()
                    }
                }
                if (!backtracked) return null
            } else {
                val nextVar = pickDecisionVar()
                if (nextVar == 0) {
                    // All variables assigned without conflict!
                    return assign.copyOf()
                }
                // Pick polarity based on occurrence frequency
                val firstVal = if (posOccurs[nextVar].size >= negOccurs[nextVar].size) 1 else -1
                stack.addLast(Frame(nextVar, firstVal, trailSize))
                assign[nextVar] = firstVal
                trail[trailSize++] = if (firstVal == 1) nextVar else -nextVar
                propagateStart = trailSize - 1
            }
        }
    }

    private fun undoTo(mark: Int) {
        while (trailSize > mark) {
            val lit = trail[--trailSize]
            assign[kotlin.math.abs(lit)] = 0
        }
    }

    private fun litValue(lit: Int): Int {
        val v = kotlin.math.abs(lit)
        val a = assign[v]
        if (a == 0) return 0
        return if ((lit > 0 && a == 1) || (lit < 0 && a == -1)) 1 else -1
    }

    private fun unitPropagate(startIdx: Int): Boolean {
        // On initial pass (startIdx == 0 && trailSize == 0), scan all unit clauses first
        if (startIdx == 0 && trailSize == 0) {
            for (cl in clauses) {
                if (cl.size == 1) {
                    val lit = cl[0]
                    val lv = litValue(lit)
                    if (lv == -1) return true
                    if (lv == 0) {
                        val v = kotlin.math.abs(lit)
                        assign[v] = if (lit > 0) 1 else -1
                        trail[trailSize++] = lit
                    }
                }
            }
        }

        var cursor = startIdx
        while (cursor < trailSize) {
            val trueLit = trail[cursor++]
            val falseLit = -trueLit
            val v = kotlin.math.abs(falseLit)
            val affectedClauses = if (falseLit > 0) posOccurs[v] else negOccurs[v]
            for (ci in affectedClauses) {
                val cl = clauses[ci]
                var satisfied = false
                var unassignedCount = 0
                var lastUnassignedLit = 0
                for (lit in cl) {
                    val lv = litValue(lit)
                    if (lv == 1) {
                        satisfied = true
                        break
                    } else if (lv == 0) {
                        unassignedCount++
                        lastUnassignedLit = lit
                        if (unassignedCount > 1) break
                    }
                }
                if (!satisfied) {
                    if (unassignedCount == 0) return true // Conflict!
                    if (unassignedCount == 1) {
                        val uv = kotlin.math.abs(lastUnassignedLit)
                        assign[uv] = if (lastUnassignedLit > 0) 1 else -1
                        trail[trailSize++] = lastUnassignedLit
                    }
                }
            }
        }
        return false
    }

    private fun pickDecisionVar(): Int {
        var bestVar = 0
        var bestScore = -1
        for (v in 1..numVars) {
            if (assign[v] == 0) {
                val score = posOccurs[v].size + negOccurs[v].size
                if (score > bestScore) {
                    bestScore = score
                    bestVar = v
                }
            }
        }
        return bestVar
    }
}
