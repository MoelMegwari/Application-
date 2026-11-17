package com.circuitgraph.app.core

/**
 * Circuit utilities and linter, ported from `circuitgraph/utils.py`.
 */
data class LintReport(
    val errors: List<String>,
    val warnings: List<String> = emptyList()
) {
    val isValid: Boolean get() = errors.isEmpty()
}

object CircuitUtils {
    fun clog2(num: Int): Int {
        if (num < 1) throw IllegalArgumentException("expected num >= 1")
        var accum = 0
        var shifter = 1
        while (num > shifter) {
            shifter = shifter shl 1
            accum++
        }
        return accum
    }

    fun intToBin(i: Int, w: Int, lend: Boolean = false): List<Boolean> {
        val bits = (0 until w).map { bit ->
            val shift = if (lend) bit else (w - 1 - bit)
            ((i shr shift) and 1) == 1
        }
        return bits
    }

    fun binToInt(b: List<Boolean>, lend: Boolean = false): Int {
        var result = 0
        val ordered = if (lend) b.reversed() else b
        for (bit in ordered) {
            result = (result shl 1) or (if (bit) 1 else 0)
        }
        return result
    }

    fun lint(
        c: Circuit,
        failFast: Boolean = false,
        unloaded: Boolean = false,
        undriven: Boolean = true,
        singleInputGates: Boolean = false
    ): LintReport {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        fun handle(msg: String) {
            if (failFast) throw IllegalArgumentException(msg)
            errors.add(msg)
        }

        val zeroInputTypes = setOf("input", "0", "1", "x", "bb_output")
        val singleInputTypes = setOf("buf", "not", "bb_input")
        val multiInputTypes = setOf("and", "nand", "or", "nor", "xor", "xnor")

        for (g in c.nodes()) {
            val t = try {
                c.type(g)
            } catch (_: Exception) {
                handle("no type for node '$g'")
                continue
            }

            if (t !in GateTypes.supportedTypes) {
                handle("node '$g' has unsupported type '$t'")
            }
            if ('.' in g && g.substringBefore('.') !in c.blackboxes) {
                handle("node '$g' has blackbox syntax with no instance")
            }

            if (t in zeroInputTypes && c.fanin(g).isNotEmpty()) {
                handle("'$t' node '$g' has fanin")
            }

            if (t == "bb_output") {
                val fo = c.fanout(g)
                if (fo.size > 1) {
                    handle("'$t' node '$g' has fanout greater than 1")
                }
                if (fo.isNotEmpty() && c.type(fo.first()) != "buf") {
                    handle("'$t' node '$g' has non-buf fanout")
                }
            }

            if (t in singleInputTypes && c.fanin(g).size > 1) {
                handle("'$t' node '$g' has fanin count > 1")
            }

            if (undriven && t in (singleInputTypes + multiInputTypes) && c.fanin(g).isEmpty()) {
                handle("'$t' node '$g' has no fanin")
            }

            if (singleInputGates && t in multiInputTypes && c.fanin(g).size < 2) {
                handle("'$t' node '$g' has fanin less than 2")
            }

            if (!c.isOutput(g) && c.fanout(g).isEmpty() && t != "bb_input") {
                val msg = "'$t' node '$g' has no fanout"
                if (unloaded) handle(msg) else warnings.add(msg)
            }
        }

        for ((name, bb) in c.blackboxes) {
            for (g in bb.inputs) {
                val key = "$name.$g"
                if (key !in c) {
                    handle("missing blackbox pin '$key'")
                } else if (c.type(key) != "bb_input") {
                    handle("blackbox pin '$key' has incorrect type '${c.type(key)}'")
                }
            }
            for (g in bb.outputs) {
                val key = "$name.$g"
                if (key !in c) {
                    handle("missing blackbox pin '$key'")
                } else if (c.type(key) != "bb_output") {
                    handle("blackbox pin '$key' has incorrect type '${c.type(key)}'")
                }
            }
        }

        return LintReport(errors = errors, warnings = warnings)
    }
}
