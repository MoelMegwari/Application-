package com.circuitgraph.app.core

/**
 * Collection of parametric logic circuit generators, ported from `circuitgraph/logic.py`.
 */
object LogicBuilders {

    fun halfAdder(): Circuit {
        val c = Circuit(name = "half_adder")
        val x = c.add("x", "input")
        val y = c.add("y", "input")
        c.add("c", "and", fanin = listOf(x, y), output = true)
        c.add("s", "xor", fanin = listOf(x, y), output = true)
        return c
    }

    fun fullAdder(): Circuit {
        val c = Circuit(name = "full_adder")
        c.add("x", "input")
        c.add("y", "input")
        c.add("cin", "input")

        c.addSubcircuit(halfAdder(), "x_y_ha", connections = mapOf("x" to "x", "y" to "y"))
        c.addSubcircuit(halfAdder(), "cin_s_ha", connections = mapOf("x" to "x_y_ha_s", "y" to "cin"))

        c.add("cout", "or", fanin = listOf("x_y_ha_c", "cin_s_ha_c"), output = true)
        c.add("s", "buf", fanin = "cin_s_ha_s", output = true)
        return c
    }

    fun adder(width: Int, carryIn: Boolean = false, carryOut: Boolean = false): Circuit {
        val w = width.coerceIn(1, 16)
        val c = Circuit(name = "adder_$w")
        var carry = c.add("cin", if (carryIn) "input" else "0")
        for (bit in 0 until w) {
            val a = c.add("a_$bit", "input")
            val b = c.add("b_$bit", "input")
            val out = c.add("out_$bit", "buf", output = true)
            c.addSubcircuit(
                fullAdder(),
                "fa_$bit",
                mapOf("x" to a, "y" to b, "cin" to carry, "s" to out)
            )
            carry = "fa_${bit}_cout"
        }
        if (carryOut) {
            c.add("cout", "buf", fanin = carry, output = true)
        }
        return c
    }

    fun mux(width: Int): Circuit {
        val w = width.coerceIn(2, 16)
        val c = Circuit(name = "mux_$w")
        for (i in 0 until w) {
            c.add("in_$i", "input")
        }
        val selBits = CircuitUtils.clog2(w)
        val sels = mutableListOf<List<String>>()
        for (i in 0 until selBits) {
            c.add("sel_$i", "input")
            c.add("not_sel_$i", "not", fanin = "sel_$i")
            sels.add(listOf("not_sel_$i", "sel_$i"))
        }

        c.add("out", "or", output = true)

        fun product(lists: List<List<String>>): List<List<String>> {
            if (lists.isEmpty()) return listOf(emptyList())
            val first = lists.first()
            val rest = product(lists.drop(1))
            val res = mutableListOf<List<String>>()
            for (item in first) {
                for (r in rest) {
                    res.add(listOf(item) + r)
                }
            }
            return res
        }

        val combos = product(sels.reversed())
        var idx = 0
        for (sel in combos) {
            if (idx >= w) break
            c.add("and_$idx", "and", fanin = sel + "in_$idx", fanout = listOf("out"))
            idx++
        }
        return c
    }

    fun popcount(width: Int): Circuit {
        val w = width.coerceIn(2, 16)
        val c = Circuit(name = "popcount_$w")
        val ps = ArrayDeque<List<String>>()
        for (i in 0 until w) {
            ps.addLast(listOf(c.add("in_$i", "input")))
        }
        c.add("tie0", "0")

        var i = 0
        while (ps.size > 1) {
            val ns = ps.removeFirst().toMutableList()
            val ms = ps.removeFirst().toMutableList()
            val aw = maxOf(ns.size, ms.size)
            while (ms.size < aw) ms.add("tie0")
            while (ns.size < aw) ns.add("tie0")

            c.addSubcircuit(adder(aw, carryOut = true), "add_$i")
            c.relabel(mapOf("add_${i}_cout" to "add_${i}_out_$aw"))
            for (j in 0 until aw) {
                c.connect(ns[j], "add_${i}_a_$j")
                c.connect(ms[j], "add_${i}_b_$j")
            }
            ps.addLast((0..aw).map { j -> "add_${i}_out_$j" })
            i++
        }

        val finalOutputs = ps.first()
        for ((idx, o) in finalOutputs.withIndex()) {
            c.add("out_$idx", "buf", fanin = o, output = true)
        }
        if (c.fanout("tie0").isEmpty()) {
            c.remove("tie0")
        }
        return c
    }
}
