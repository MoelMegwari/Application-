package com.circuitgraph.app.core

/**
 * Circuit transformation functions, ported from `circuitgraph/tx.py`.
 */
object CircuitTransforms {

    fun stripIo(c: Circuit): Circuit {
        val out = c.copy()
        for (i in out.inputs()) out.setType(i, "buf")
        for (o in out.outputs()) out.setOutput(o, false)
        return out
    }

    fun stripOutputs(c: Circuit): Circuit {
        val out = c.copy()
        for (o in out.outputs()) out.setOutput(o, false)
        return out
    }

    fun stripInputs(c: Circuit): Circuit {
        val out = c.copy()
        for (i in out.inputs()) out.setType(i, "buf")
        return out
    }

    fun stripBlackboxes(c: Circuit, ignorePins: Set<String> = emptySet()): Circuit {
        val out = c.copy()
        val mapping = LinkedHashMap<String, String>()

        for (n in out.filterType("bb_input")) {
            val pin = n.substringAfterLast('.')
            if (pin in ignorePins) {
                out.remove(n)
            } else {
                out.setType(n, "buf")
                out.setOutput(n, true)
                mapping[n] = n.replace('.', '_')
            }
        }
        for (n in out.filterType("bb_output")) {
            val pin = n.substringAfterLast('.')
            if (pin in ignorePins) {
                out.remove(n)
            } else {
                out.setType(n, "input")
                mapping[n] = n.replace('.', '_')
            }
        }
        for (k in mapping.values) {
            if (k in out) throw IllegalArgumentException("Overlapping blackbox name: $k")
        }
        out.relabel(mapping)
        out.blackboxes.clear()
        return out
    }

    fun relabel(c: Circuit, mapping: Map<String, String>): Circuit {
        val out = c.copy()
        out.relabel(mapping)
        return out
    }

    fun subcircuit(c: Circuit, nodes: Set<String>, modifyIo: Boolean = false): Circuit {
        val sc = Circuit(name = "${c.name}_sub")
        for (node in nodes) {
            val t = c.type(node)
            if (t in listOf("bb_output", "bb_input")) {
                throw IllegalArgumentException("Cannot create a subcircuit with blackboxes")
            }
            sc.add(node, t, output = c.isOutput(node))
        }
        for ((u, v) in c.edges()) {
            if (u in nodes && v in nodes) {
                sc.connect(u, v)
            }
        }
        if (modifyIo) {
            for (node in sc.orderedNodes()) {
                if (sc.type(node) !in listOf("0", "1", "x") && sc.fanin(node).isEmpty()) {
                    sc.setType(node, "input")
                }
                if (sc.fanout(node).isEmpty()) {
                    sc.setOutput(node, true)
                }
            }
        }
        return sc
    }

    fun miter(
        c0: Circuit,
        c1: Circuit? = null,
        startpoints: Set<String>? = null,
        endpoints: Set<String>? = null
    ): Circuit {
        if (c0.blackboxes.isNotEmpty()) {
            throw IllegalArgumentException("${c0.name} contains a blackbox")
        }
        val second = c1 ?: c0
        if (second.blackboxes.isNotEmpty()) {
            throw IllegalArgumentException("${second.name} contains a blackbox")
        }

        val sp = startpoints ?: c0.startpoints().intersect(second.startpoints())
        val ep = endpoints ?: c0.endpoints().intersect(second.endpoints())
        if (sp.isEmpty() || ep.isEmpty()) {
            throw IllegalArgumentException("Circuits must share at least one startpoint and endpoint")
        }

        val m = Circuit(name = "miter_${c0.name}_${second.name}")
        m.addSubcircuit(c0, "c0")
        m.addSubcircuit(second, "c1")

        for (n in sp) {
            m.add(n, "input", fanout = listOf("c0_$n", "c1_$n"))
        }

        m.add("sat", if (ep.size > 1) "or" else "buf", output = true)
        for (n in ep) {
            m.add("dif_$n", "xor", fanin = listOf("c0_$n", "c1_$n"), fanout = listOf("sat"))
        }
        return m
    }

    fun sensitizationTransform(
        c: Circuit,
        n: String,
        endpoints: Set<String>? = null
    ): Circuit {
        if (c.blackboxes.isNotEmpty()) {
            throw IllegalArgumentException("Circuit contains a blackbox")
        }
        val subc: Circuit
        val miterName: String
        if (!endpoints.isNullOrEmpty()) {
            val fi = c.transitiveFanin(endpoints)
            if (n !in fi && n !in endpoints) {
                throw IllegalArgumentException("'$n' is not in fanin of given endpoints")
            }
            subc = subcircuit(c, endpoints + fi)
            for (node in subc.orderedNodes()) {
                subc.setOutput(node, node in endpoints)
            }
            miterName = "${c.name}_sensitize_${n}_to_${endpoints.joinToString("_")}"
        } else {
            subc = c
            miterName = "${c.name}_sensitize_$n"
        }

        val m = miter(subc)
        m.name = miterName
        m.disconnect(m.fanin("c1_$n"), "c1_$n")
        m.setType("c1_$n", "not")
        m.connect("c0_$n", "c1_$n")
        return m
    }

    fun sensitivityTransform(c: Circuit, n: String): Circuit {
        if (c.blackboxes.isNotEmpty()) {
            throw IllegalArgumentException("${c.name} contains a blackbox")
        }
        val startpoints = c.startpoints(n)
        if (startpoints.isEmpty()) {
            throw IllegalArgumentException("$n has no startpoints")
        }

        val fiNodes = c.transitiveFanin(n) + n
        val subC = subcircuit(c, fiNodes)
        val sen = Circuit(name = "${c.name}_sensitivity_$n")
        sen.addSubcircuit(subC, "orig")
        for (s in startpoints) {
            sen.add(s, "input", fanout = listOf("orig_$s"))
        }

        sen.addSubcircuit(LogicBuilders.popcount(startpoints.size.coerceAtLeast(2)), "pc")

        startpoints.forEachIndexed { idx, s0 ->
            sen.addSubcircuit(subC, "inv_$s0")
            for (s1 in startpoints) {
                if (s0 != s1) {
                    sen.connect(s1, "inv_${s0}_$s1")
                } else {
                    sen.setType("inv_${s0}_$s1", "not")
                    sen.connect(s0, "inv_${s0}_$s1")
                }
            }
            sen.add(
                "dif_out_$s0",
                "xor",
                fanin = listOf("orig_$n", "inv_${s0}_$n"),
                fanout = listOf("pc_in_$idx"),
                output = true
            )
        }

        val outBits = CircuitUtils.clog2(startpoints.size + 1)
        for (o in 0 until outBits) {
            sen.add("sen_out_$o", "buf", fanin = "pc_out_$o", output = true)
        }
        return sen
    }

    fun limitFanin(c: Circuit, k: Int): Circuit {
        if (k < 2) throw IllegalArgumentException("'k' must be >= 2, not '$k'")
        val gatemap = mapOf(
            "and" to "and",
            "nand" to "and",
            "or" to "or",
            "nor" to "or",
            "xor" to "xor",
            "xnor" to "xnor"
        )
        val ck = c.copy()
        ck.name = "${c.name}_fanin$k"
        for (n in ck.orderedNodes()) {
            val gateType = gatemap[ck.type(n)] ?: continue
            var i = 0
            while (ck.fanin(n).size > k) {
                val fi = ck.fanin(n).toList()
                val f0 = fi[0]
                val f1 = fi[1]
                ck.disconnect(listOf(f0, f1), n)
                ck.add(
                    n = "${n}_limit_fanin_$i",
                    nodeType = gateType,
                    fanin = listOf(f0, f1),
                    fanout = listOf(n),
                    uid = true
                )
                i++
            }
        }
        return ck
    }

    fun limitFanout(c: Circuit, k: Int): Circuit {
        if (k < 2) throw IllegalArgumentException("'k' must be >= 2, not '$k'")
        val ck = c.copy()
        ck.name = "${c.name}_fanout$k"
        for (n in ck.orderedNodes()) {
            var i = 0
            while (ck.fanout(n).size > k) {
                val fo = ck.fanout(n).toList()
                val f0 = fo[0]
                val f1 = fo[1]
                ck.disconnect(n, listOf(f0, f1))
                ck.add(
                    n = "${n}_limit_fanout_$i",
                    nodeType = "buf",
                    fanin = listOf(n),
                    fanout = listOf(f0, f1),
                    uid = true
                )
                i++
            }
        }
        return ck
    }

    fun unroll(
        c: Circuit,
        n: Int,
        stateIo: Map<String, String>,
        prefix: String = "cg_unroll"
    ): Pair<Circuit, Map<String, List<String>>> {
        if (c.blackboxes.isNotEmpty()) {
            throw IllegalArgumentException("${c.name} contains a blackbox")
        }
        if (n < 1) throw IllegalArgumentException("n must be >= 1 ($n)")

        val cIo = c.io()
        for ((k, v) in stateIo) {
            if (k !in cIo) throw IllegalArgumentException("Node '$k' in stateIo but not in io")
            if (v !in cIo) throw IllegalArgumentException("Node '$v' in stateIo but not in io")
        }

        val uc = Circuit(name = "${c.name}_unrolled_$n")
        val ioMap = cIo.associateWith { mutableListOf<String>() }

        for (itr in 0 until n) {
            val conns = LinkedHashMap<String, String>()
            for (io in cIo) {
                val newIo = uc.uid("${io}_${prefix}_$itr")
                val t = when {
                    io in stateIo || io in stateIo.values -> "buf"
                    io in c.inputs() -> "input"
                    else -> "buf"
                }
                uc.add(newIo, t, output = c.isOutput(io))
                ioMap[io]!!.add(newIo)
                conns[io] = newIo
            }
            uc.addSubcircuit(c, "unrolled_$itr", conns)

            if (itr == 0) {
                for (v in stateIo.values) {
                    uc.setType(ioMap[v]!![0], "input")
                }
            } else {
                for ((k, v) in stateIo) {
                    uc.connect(ioMap[k]!![itr - 1], ioMap[v]!![itr])
                }
            }
        }
        return uc to ioMap
    }

    fun sequentialUnroll(
        c: Circuit,
        n: Int,
        regDPort: String = "D",
        regQPort: String = "Q",
        ignorePins: Set<String> = emptySet(),
        addFlopOutputs: Boolean = false,
        initialValue: String? = null,
        removeUnloaded: Boolean = true,
        prefix: String = "cg_unroll"
    ): Pair<Circuit, Map<String, List<String>>> {
        if (c.blackboxes.isEmpty()) {
            throw IllegalArgumentException("${c.name} has no sequential blackboxes")
        }
        val firstBb = c.blackboxes.values.first()
        val actualD = if (regDPort in firstBb.inputs) regDPort else firstBb.inputs.firstOrNull { it.equals("d", true) }
            ?: throw IllegalArgumentException("Provided d port $regDPort not in blackbox inputs")
        val actualQ = if (regQPort in firstBb.outputs) regQPort else firstBb.outputs.firstOrNull { it.equals("q", true) }
            ?: throw IllegalArgumentException("Provided q port $regQPort not in blackbox outputs")

        val cs = stripBlackboxes(c, ignorePins)
        for (bbName in c.blackboxes.keys) {
            for (p in firstBb.inputs - actualD) cs.remove("${bbName}_$p")
            for (p in firstBb.outputs - actualQ) cs.remove("${bbName}_$p")
        }
        if (removeUnloaded) {
            for (i in cs.inputs()) {
                if (cs.fanout(i).isEmpty()) cs.remove(i)
            }
        }

        val stateIo = c.blackboxes.keys.associate { "${it}_$actualD" to "${it}_$actualQ" }
        val (uc, ioMap) = unroll(cs, n, stateIo, prefix)

        for (bbName in c.blackboxes.keys) {
            ioMap["${bbName}_$actualD"]?.let { uc.setOutput(it, addFlopOutputs) }
        }
        if (initialValue != null) {
            for (bbName in c.blackboxes.keys) {
                ioMap["${bbName}_$actualQ"]?.firstOrNull()?.let { uc.setType(it, initialValue) }
            }
        }
        return uc to ioMap
    }

    fun acyclicUnroll(c: Circuit): Circuit {
        if (c.blackboxes.isNotEmpty()) {
            throw IllegalArgumentException("Cannot perform acyclic unroll with blackboxes")
        }
        if (!c.isCyclic()) return c.copy()

        // Greedy feedback arc set heuristic (Eades, Lin, Smyth) matching tx.py
        val gCopy = c.copy()
        val s1 = mutableListOf<String>()
        val s2 = mutableListOf<String>()

        while (gCopy.size > 0) {
            var changed = true
            while (changed) {
                changed = false
                val sinks = gCopy.orderedNodes().filter { gCopy.fanout(it).isEmpty() }
                if (sinks.isNotEmpty()) {
                    s2.addAll(sinks)
                    gCopy.remove(sinks)
                    changed = true
                }
                val sources = gCopy.orderedNodes().filter { gCopy.fanin(it).isEmpty() }
                if (sources.isNotEmpty()) {
                    s1.addAll(sources)
                    gCopy.remove(sources)
                    changed = true
                }
            }
            if (gCopy.size > 0) {
                val chosen = gCopy.orderedNodes().maxBy { gCopy.fanout(it).size - gCopy.fanin(it).size }
                s1.add(chosen)
                gCopy.remove(chosen)
            }
        }

        val ordering = s1 + s2.reversed()
        val indexMap = ordering.withIndex().associate { (idx, node) -> node to idx }
        val feedback = c.edges()
            .filter { (u, v) -> (indexMap[u] ?: 0) >= (indexMap[v] ?: 0) && (u == v || u in c.transitiveFanout(v)) }
            .map { it.first }
            .toSet()

        val sp = c.startpoints()
        val acyc = Circuit(name = "acyc_${c.name}")
        for (n in sp) acyc.add(n, "input")

        val cCut = c.copy()
        for (f in feedback) {
            val fo = c.fanout(f)
            cCut.disconnect(f, fo)
            cCut.add("aux_in_$f", "buf", fanout = fo)
        }
        cCut.setOutput(c.outputs(), false)

        val iters = feedback.size + 1
        for (i in 0 until iters) {
            acyc.addSubcircuit(cCut, "c$i", sp.associateWith { it })
            if (i > 0) {
                for (f in feedback) {
                    acyc.connect("c${i - 1}_$f", "c${i}_aux_in_$f")
                }
            } else {
                for (f in feedback) {
                    acyc.setType("c0_aux_in_$f", "input")
                }
            }
        }

        val lastIdx = iters - 1
        for (o in c.outputs()) {
            acyc.add(o, "buf", fanin = "c${lastIdx}_$o", output = true)
        }
        if (acyc.isCyclic()) {
            throw IllegalStateException("Circuit still cyclic after acyclic unroll")
        }
        return acyc
    }

    fun ternary(c: Circuit): Pair<Circuit, Map<String, String>> {
        if (c.blackboxes.isNotEmpty()) {
            throw IllegalArgumentException("${c.name} contains a blackbox")
        }
        val t = c.copy()
        t.name = "${c.name}_ternary"
        val mapping = c.orderedNodes().associateWith { c.uid("${it}_X") }

        for (n in c.orderedNodes()) {
            val mapped = mapping[n]!!
            val fi = c.fanin(n)
            when (c.type(n)) {
                "and", "nand" -> {
                    t.add(mapped, "and", output = c.isOutput(n), allowRedefinition = true)
                    t.add(
                        "${n}_x_in_fi",
                        "or",
                        fanin = fi.map { mapping[it]!! },
                        fanout = listOf(mapped),
                        uid = true,
                        addConnectedNodes = true
                    )
                    val zeroNotInFi = t.add("${n}_0_not_in_fi", "nor", fanout = listOf(mapped), uid = true)
                    for (p in fi) {
                        t.add("${p}_is_0", "nor", fanin = listOf(p, mapping[p]!!), fanout = listOf(zeroNotInFi), uid = true)
                    }
                }
                "or", "nor" -> {
                    t.add(mapped, "and", output = c.isOutput(n), allowRedefinition = true)
                    t.add(
                        "${n}_x_in_fi",
                        "or",
                        fanin = fi.map { mapping[it]!! },
                        fanout = listOf(mapped),
                        uid = true,
                        addConnectedNodes = true
                    )
                    val oneNotInFi = t.add("${n}_1_not_in_fi", "nor", fanout = listOf(mapped), uid = true)
                    for (p in fi) {
                        val isOne = t.add("${p}_is_1", "and", fanin = listOf(p), fanout = listOf(oneNotInFi), uid = true)
                        t.add("${p}_not_x", "not", fanin = listOf(mapping[p]!!), fanout = listOf(isOne), uid = true)
                    }
                }
                "buf", "not" -> {
                    val p = fi.firstOrNull()
                    t.add(
                        mapped,
                        "buf",
                        fanin = if (p != null) listOf(mapping[p]!!) else emptyList(),
                        output = c.isOutput(n),
                        addConnectedNodes = true,
                        allowRedefinition = true
                    )
                }
                "xor", "xnor" -> {
                    t.add(
                        mapped,
                        "or",
                        fanin = fi.map { mapping[it]!! },
                        output = c.isOutput(n),
                        addConnectedNodes = true,
                        allowRedefinition = true
                    )
                }
                "0", "1" -> t.add(mapped, "0", output = c.isOutput(n), allowRedefinition = true)
                "input" -> t.add(mapped, "input", allowRedefinition = true)
                else -> throw IllegalArgumentException("Node '$n' has invalid type '${c.type(n)}'")
            }
        }
        return t to mapping
    }

    /**
     * Pure-Kotlin And-Inverter Graph (AIG) mapping: converts all combinational gates
     * into 2-input `and` and 1-input `not` primitives.
     */
    fun toAig(c: Circuit): Circuit {
        if (c.blackboxes.isNotEmpty()) {
            throw IllegalArgumentException("${c.name} contains a blackbox; strip blackboxes first")
        }
        val limited = limitFanin(c, 2)
        val aig = Circuit(name = "${c.name}_aig")

        for (n in limited.orderedNodes()) {
            val t = limited.type(n)
            if (t in listOf("input", "0", "1", "x")) {
                aig.add(n, t, output = limited.isOutput(n))
            }
        }

        val order = if (!limited.isCyclic()) limited.topoSort() else limited.orderedNodes()
        for (n in order) {
            val t = limited.type(n)
            if (t in listOf("input", "0", "1", "x")) continue
            val fi = limited.fanin(n).toList()
            val isOut = limited.isOutput(n)
            when (t) {
                "buf" -> {
                    if (fi.size == 1) {
                        val inv = aig.add("${n}_aig_inv", "not", fanin = fi[0], uid = true)
                        aig.add(n, "not", fanin = inv, output = isOut)
                    } else {
                        aig.add(n, "0", output = isOut)
                    }
                }
                "not" -> aig.add(n, "not", fanin = fi, output = isOut)
                "and" -> aig.add(n, "and", fanin = fi, output = isOut)
                "nand" -> {
                    val andNode = aig.add("${n}_aig_and", "and", fanin = fi, uid = true)
                    aig.add(n, "not", fanin = andNode, output = isOut)
                }
                "or" -> {
                    if (fi.size == 1) {
                        val inv = aig.add("${n}_aig_inv", "not", fanin = fi[0], uid = true)
                        aig.add(n, "not", fanin = inv, output = isOut)
                    } else {
                        val na = aig.add("${n}_aig_na", "not", fanin = fi[0], uid = true)
                        val nb = aig.add("${n}_aig_nb", "not", fanin = fi[1], uid = true)
                        val nandNode = aig.add("${n}_aig_and", "and", fanin = listOf(na, nb), uid = true)
                        aig.add(n, "not", fanin = nandNode, output = isOut)
                    }
                }
                "nor" -> {
                    if (fi.size == 1) {
                        aig.add(n, "not", fanin = fi[0], output = isOut)
                    } else {
                        val na = aig.add("${n}_aig_na", "not", fanin = fi[0], uid = true)
                        val nb = aig.add("${n}_aig_nb", "not", fanin = fi[1], uid = true)
                        aig.add(n, "and", fanin = listOf(na, nb), output = isOut)
                    }
                }
                "xor", "xnor" -> {
                    if (fi.size == 1) {
                        if (t == "xor") {
                            val inv = aig.add("${n}_aig_inv", "not", fanin = fi[0], uid = true)
                            aig.add(n, "not", fanin = inv, output = isOut)
                        } else {
                            aig.add(n, "not", fanin = fi[0], output = isOut)
                        }
                    } else {
                        val a = fi[0]
                        val b = fi[1]
                        val na = aig.add("${n}_aig_na", "not", fanin = a, uid = true)
                        val nb = aig.add("${n}_aig_nb", "not", fanin = b, uid = true)
                        val t1 = aig.add("${n}_aig_t1", "and", fanin = listOf(a, nb), uid = true)
                        val t2 = aig.add("${n}_aig_t2", "and", fanin = listOf(na, b), uid = true)
                        val nt1 = aig.add("${n}_aig_nt1", "not", fanin = t1, uid = true)
                        val nt2 = aig.add("${n}_aig_nt2", "not", fanin = t2, uid = true)
                        val both = aig.add("${n}_aig_both", "and", fanin = listOf(nt1, nt2), uid = true)
                        if (t == "xor") {
                            aig.add(n, "not", fanin = both, output = isOut)
                        } else {
                            aig.add(n, "buf", fanin = both, output = isOut)
                            // keep strict AIG by double-not
                            aig.setType(n, "not")
                            val inv = aig.add("${n}_aig_xnor_inv", "not", fanin = both, uid = true)
                            aig.disconnect(both, n)
                            aig.connect(inv, n)
                        }
                    }
                }
            }
        }
        return aig
    }

    fun insertRegisters(c: Circuit, numStages: Int): Circuit {
        if (c.isCyclic()) throw IllegalArgumentException("Cannot insert pipeline registers into cyclic circuit")
        val cReg = c.copy()
        cReg.name = "${c.name}_pipelined_$numStages"
        val nodesAtDepths = mutableListOf<MutableList<String>>()
        var maxDepth = 0
        for (n in cReg.orderedNodes()) {
            val depth = cReg.faninDepth(n)
            while (depth >= nodesAtDepths.size) nodesAtDepths.add(mutableListOf())
            nodesAtDepths[depth].add(n)
            if (depth > maxDepth) maxDepth = depth
        }
        val depthInc = maxOf(1, kotlin.math.round(maxDepth.toDouble() / (numStages + 1)).toInt())
        if ("clk" !in cReg) cReg.add("clk", "input")

        val ff = BlackBox("ff", listOf("clk", "d"), listOf("q"))
        var i = depthInc
        while (i < maxDepth) {
            for (n in nodesAtDepths[i]) {
                val fo = cReg.fanout(n)
                if (fo.isEmpty()) continue
                cReg.disconnect(n, fo)
                val q = cReg.add("${n}_reg_q_$i", "buf", fanout = fo, uid = true)
                cReg.addBlackbox(ff, "ff_$n", mapOf("d" to n, "q" to q, "clk" to "clk"))
            }
            i += depthInc
        }
        return cReg
    }
}
