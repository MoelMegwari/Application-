package com.circuitgraph.app.core

/**
 * Core Circuit Graph data structures, faithfully ported from `circuitgraph/circuit.py`.
 */
object GateTypes {
    val primitiveGates = listOf(
        "buf", "and", "or", "xor", "not", "nand", "nor", "xnor"
    )

    val addableTypes = primitiveGates + listOf(
        "0", "1", "x", "input"
    )

    val supportedTypes = addableTypes + listOf(
        "bb_input", "bb_output"
    )

    val zeroInputTypes = setOf("input", "0", "1", "x", "bb_output")
    val singleInputTypes = setOf("buf", "not", "bb_input")
    val multiInputTypes = setOf("and", "nand", "or", "nor", "xor", "xnor")
}

data class BlackBox(
    val name: String,
    val inputs: Set<String>,
    val outputs: Set<String>
) {
    constructor(name: String, inputs: List<String>, outputs: List<String>) : this(
        name = name,
        inputs = inputs.toSet(),
        outputs = outputs.toSet()
    )

    fun io(): Set<String> = inputs + outputs
}

class Circuit(
    var name: String = "circuit"
) {
    private val nodeTypes = LinkedHashMap<String, String>()
    private val nodeOutputs = LinkedHashMap<String, Boolean>()
    private val predecessors = LinkedHashMap<String, LinkedHashSet<String>>()
    private val successors = LinkedHashMap<String, LinkedHashSet<String>>()
    val blackboxes = LinkedHashMap<String, BlackBox>()

    operator fun contains(n: String): Boolean = nodeTypes.containsKey(n)

    val size: Int
        get() = nodeTypes.size

    fun nodes(): Set<String> = nodeTypes.keys.toSet()

    fun orderedNodes(): List<String> = nodeTypes.keys.toList()

    fun edges(): Set<Pair<String, String>> {
        val result = LinkedHashSet<Pair<String, String>>()
        for ((u, outs) in successors) {
            for (v in outs) {
                result.add(u to v)
            }
        }
        return result
    }

    fun copy(): Circuit {
        val c = Circuit(name)
        c.nodeTypes.putAll(nodeTypes)
        c.nodeOutputs.putAll(nodeOutputs)
        for ((k, v) in predecessors) {
            c.predecessors[k] = LinkedHashSet(v)
        }
        for ((k, v) in successors) {
            c.successors[k] = LinkedHashSet(v)
        }
        c.blackboxes.putAll(blackboxes)
        return c
    }

    fun setType(ns: String, t: String) {
        setType(listOf(ns), t)
    }

    fun setType(ns: Iterable<String>, t: String) {
        if (t !in GateTypes.addableTypes) {
            throw IllegalArgumentException("unsupported type $t")
        }
        for (n in ns) {
            if (n !in nodeTypes) throw NoSuchElementException("Node '$n' does not exist.")
            nodeTypes[n] = t
        }
    }

    fun type(n: String): String {
        return nodeTypes[n] ?: throw NoSuchElementException("Node '$n' does not exist.")
    }

    fun type(ns: Iterable<String>): List<String> = ns.map { type(it) }

    fun filterType(type: String): Set<String> = filterType(listOf(type))

    fun filterType(types: Iterable<String>): Set<String> {
        val typeSet = types.toSet()
        for (t in typeSet) {
            if (t !in GateTypes.supportedTypes) {
                throw IllegalArgumentException("type $t not supported.")
            }
        }
        return nodeTypes.filterValues { it in typeSet }.keys.toSet()
    }

    fun add(
        n: String,
        nodeType: String,
        fanin: Iterable<String>? = null,
        fanout: Iterable<String>? = null,
        output: Boolean = false,
        addConnectedNodes: Boolean = false,
        allowRedefinition: Boolean = false,
        uid: Boolean = false
    ): String {
        val nodeName = if (uid) uid(n) else {
            if (n in this && !allowRedefinition) {
                throw IllegalArgumentException("Node '$n' already in circuit")
            }
            n
        }

        val fi = fanin?.toList() ?: emptyList()
        val fo = fanout?.toList() ?: emptyList()

        if (nodeType !in GateTypes.supportedTypes) {
            throw IllegalArgumentException("Cannot add unknown type '$nodeType'")
        }
        if (fi.size > 1 && nodeType in listOf("buf", "not")) {
            throw IllegalArgumentException("$nodeType cannot have more than one fanin")
        }
        if (fi.isNotEmpty() && nodeType in listOf("0", "1", "x", "input")) {
            throw IllegalArgumentException("$nodeType cannot have fanin")
        }
        if (nodeName.isEmpty()) {
            throw IllegalArgumentException("Node name cannot be empty")
        }
        if (nodeName[0] in '0'..'9') {
            throw IllegalArgumentException("cannot add node starting with int: $nodeName")
        }

        if (nodeName !in nodeTypes) {
            predecessors[nodeName] = LinkedHashSet()
            successors[nodeName] = LinkedHashSet()
        }
        nodeTypes[nodeName] = nodeType
        nodeOutputs[nodeName] = output

        if (addConnectedNodes) {
            for (f in fi + fo) {
                if (f !in this) {
                    add(f, "buf")
                }
            }
        }

        if (fo.isNotEmpty()) connect(listOf(nodeName), fo)
        if (fi.isNotEmpty()) connect(fi, listOf(nodeName))

        return nodeName
    }

    fun add(
        n: String,
        nodeType: String,
        fanin: String,
        output: Boolean = false,
        addConnectedNodes: Boolean = false,
        allowRedefinition: Boolean = false,
        uid: Boolean = false
    ): String = add(
        n = n,
        nodeType = nodeType,
        fanin = listOf(fanin),
        output = output,
        addConnectedNodes = addConnectedNodes,
        allowRedefinition = allowRedefinition,
        uid = uid
    )

    fun remove(n: String) = remove(listOf(n))

    fun remove(ns: Iterable<String>) {
        for (n in ns.toList()) {
            if (n !in nodeTypes) continue
            val preds = predecessors[n]?.toList() ?: emptyList()
            val succs = successors[n]?.toList() ?: emptyList()
            for (p in preds) {
                successors[p]?.remove(n)
            }
            for (s in succs) {
                predecessors[s]?.remove(n)
            }
            predecessors.remove(n)
            successors.remove(n)
            nodeTypes.remove(n)
            nodeOutputs.remove(n)
        }
    }

    fun relabel(mapping: Map<String, String>) {
        if (mapping.isEmpty()) return
        val newNodeTypes = LinkedHashMap<String, String>()
        val newNodeOutputs = LinkedHashMap<String, Boolean>()
        val newPreds = LinkedHashMap<String, LinkedHashSet<String>>()
        val newSuccs = LinkedHashMap<String, LinkedHashSet<String>>()

        for ((k, v) in nodeTypes) {
            val mapped = mapping[k] ?: k
            newNodeTypes[mapped] = v
            newNodeOutputs[mapped] = nodeOutputs[k] ?: false
            val pSet = LinkedHashSet<String>()
            for (p in predecessors[k].orEmpty()) {
                pSet.add(mapping[p] ?: p)
            }
            newPreds[mapped] = pSet
            val sSet = LinkedHashSet<String>()
            for (s in successors[k].orEmpty()) {
                sSet.add(mapping[s] ?: s)
            }
            newSuccs[mapped] = sSet
        }

        nodeTypes.clear()
        nodeTypes.putAll(newNodeTypes)
        nodeOutputs.clear()
        nodeOutputs.putAll(newNodeOutputs)
        predecessors.clear()
        predecessors.putAll(newPreds)
        successors.clear()
        successors.putAll(newSuccs)
    }

    fun connect(u: String, v: String) = connect(listOf(u), listOf(v))

    fun connect(u: String, vs: Iterable<String>) = connect(listOf(u), vs)

    fun connect(us: Iterable<String>, v: String) = connect(us, listOf(v))

    fun connect(us: Iterable<String>, vs: Iterable<String>) {
        val uList = us.toList()
        val vList = vs.toList()
        if (uList.isEmpty() || vList.isEmpty()) return

        for (n in uList) {
            if (n !in nodeTypes) throw IllegalArgumentException("node '$n' does not exist.")
        }
        for (n in vList) {
            if (n !in nodeTypes) throw IllegalArgumentException("node '$n' does not exist.")
        }

        for (v in vList) {
            val t = type(v)
            if (t in listOf("input", "0", "1", "x", "bb_output")) {
                throw IllegalArgumentException("cannot connect to $t '$v'")
            }
            if (t in listOf("bb_input", "buf", "not")) {
                val newInputs = uList.count { it !in predecessors[v].orEmpty() }
                if (fanin(v).size + newInputs > 1) {
                    throw IllegalArgumentException("fanin of $t '$v' cannot be greater than 1.")
                }
            }
        }
        for (u in uList) {
            val t = type(u)
            if (t == "bb_input") {
                throw IllegalArgumentException("cannot connect from $t '$u'.")
            }
            if (t == "bb_output") {
                for (v in vList) {
                    if (type(v) != "buf") {
                        throw IllegalArgumentException("cannot connect from $t '$u' to non-buf '$v'")
                    }
                }
                val newOutputs = vList.count { it !in successors[u].orEmpty() }
                if (fanout(u).size + newOutputs > 1) {
                    throw IllegalArgumentException("fanout of $t '$u' cannot be greater than 1.")
                }
            }
        }

        for (u in uList) {
            for (v in vList) {
                successors.getOrPut(u) { LinkedHashSet() }.add(v)
                predecessors.getOrPut(v) { LinkedHashSet() }.add(u)
            }
        }
    }

    fun disconnect(u: String, v: String) = disconnect(listOf(u), listOf(v))

    fun disconnect(u: String, vs: Iterable<String>) = disconnect(listOf(u), vs)

    fun disconnect(us: Iterable<String>, v: String) = disconnect(us, listOf(v))

    fun disconnect(us: Iterable<String>, vs: Iterable<String>) {
        val uList = us.toList()
        val vList = vs.toList()
        for (u in uList) {
            for (v in vList) {
                successors[u]?.remove(v)
                predecessors[v]?.remove(u)
            }
        }
    }

    fun fanin(n: String): Set<String> = predecessors[n]?.toSet() ?: emptySet()

    fun fanin(ns: Iterable<String>): Set<String> {
        val result = LinkedHashSet<String>()
        for (n in ns) {
            result.addAll(predecessors[n].orEmpty())
        }
        return result
    }

    fun fanout(n: String): Set<String> = successors[n]?.toSet() ?: emptySet()

    fun fanout(ns: Iterable<String>): Set<String> {
        val result = LinkedHashSet<String>()
        for (n in ns) {
            result.addAll(successors[n].orEmpty())
        }
        return result
    }

    fun transitiveFanin(n: String): Set<String> = transitiveFanin(listOf(n))

    fun transitiveFanin(ns: Iterable<String>): Set<String> {
        val visited = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        for (n in ns) {
            for (p in predecessors[n].orEmpty()) {
                if (visited.add(p)) queue.addLast(p)
            }
        }
        while (queue.isNotEmpty()) {
            val curr = queue.removeFirst()
            for (p in predecessors[curr].orEmpty()) {
                if (visited.add(p)) queue.addLast(p)
            }
        }
        return visited
    }

    fun transitiveFanout(n: String): Set<String> = transitiveFanout(listOf(n))

    fun transitiveFanout(ns: Iterable<String>): Set<String> {
        val visited = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        for (n in ns) {
            for (s in successors[n].orEmpty()) {
                if (visited.add(s)) queue.addLast(s)
            }
        }
        while (queue.isNotEmpty()) {
            val curr = queue.removeFirst()
            for (s in successors[curr].orEmpty()) {
                if (visited.add(s)) queue.addLast(s)
            }
        }
        return visited
    }

    fun fanoutDepth(n: String, maximum: Boolean = true): Int = fanoutDepth(listOf(n), maximum)

    fun fanoutDepth(ns: Iterable<String>, maximum: Boolean = true): Int {
        if (isCyclic()) throw IllegalStateException("Cannot compute depth of cyclic circuit")
        val nsList = ns.toList()
        if (nsList.isEmpty()) return 0
        val reachable = transitiveFanout(nsList)
        val visited = LinkedHashMap<String, Int>()
        for (n in nsList) visited[n] = 0

        fun visitNode(node: String, depth: Int) {
            val prev = visited[node]
            visited[node] = if (prev != null) {
                if (maximum) maxOf(prev, depth) else minOf(prev, depth)
            } else {
                depth
            }
            val reachableFanin = fanin(node).intersect(reachable)
            if (reachableFanin.all { it in visited }) {
                val curDepth = visited[node]!!
                for (fo in fanout(node)) {
                    visitNode(fo, curDepth + 1)
                }
            }
        }

        for (f in fanout(nsList)) {
            visitNode(f, 1)
        }
        return if (maximum) visited.values.maxOrNull() ?: 0 else visited.values.minOrNull() ?: 0
    }

    fun faninDepth(n: String, maximum: Boolean = true): Int = faninDepth(listOf(n), maximum)

    fun faninDepth(ns: Iterable<String>, maximum: Boolean = true): Int {
        if (isCyclic()) throw IllegalStateException("Cannot compute depth of cyclic circuit")
        val nsList = ns.toList()
        if (nsList.isEmpty()) return 0
        val reachable = transitiveFanin(nsList)
        val visited = LinkedHashMap<String, Int>()
        for (n in nsList) visited[n] = 0

        fun visitNode(node: String, depth: Int) {
            val prev = visited[node]
            visited[node] = if (prev != null) {
                if (maximum) maxOf(prev, depth) else minOf(prev, depth)
            } else {
                depth
            }
            val reachableFanout = fanout(node).intersect(reachable)
            if (reachableFanout.all { it in visited }) {
                val curDepth = visited[node]!!
                for (fi in fanin(node)) {
                    visitNode(fi, curDepth + 1)
                }
            }
        }

        for (f in fanin(nsList)) {
            visitNode(f, 1)
        }
        return if (maximum) visited.values.maxOrNull() ?: 0 else visited.values.minOrNull() ?: 0
    }

    fun paths(source: String, target: String, cutoff: Int? = null, maxPaths: Int = 200): List<List<String>> {
        val results = mutableListOf<List<String>>()
        val current = mutableListOf(source)
        val onPath = HashSet<String>().apply { add(source) }

        fun dfs(u: String) {
            if (results.size >= maxPaths) return
            if (u == target) {
                results.add(current.toList())
                return
            }
            if (cutoff != null && current.size - 1 >= cutoff) return
            for (v in fanout(u)) {
                if (v !in onPath) {
                    onPath.add(v)
                    current.add(v)
                    dfs(v)
                    current.removeAt(current.lastIndex)
                    onPath.remove(v)
                }
            }
        }

        dfs(source)
        return results
    }

    fun inputs(): Set<String> = filterType("input")

    fun isOutput(node: String): Boolean {
        if (node !in nodeTypes) throw NoSuchElementException("Node $node does not exist.")
        return nodeOutputs[node] ?: false
    }

    fun setOutput(n: String, output: Boolean = true) = setOutput(listOf(n), output)

    fun setOutput(ns: Iterable<String>, output: Boolean = true) {
        for (n in ns) {
            if (n !in nodeTypes) throw NoSuchElementException("Node $n does not exist.")
            nodeOutputs[n] = output
        }
    }

    fun outputs(): Set<String> = nodeTypes.keys.filter { isOutput(it) }.toSet()

    fun io(): Set<String> = inputs() + outputs()

    fun startpoints(ns: String? = null): Set<String> {
        val allStarts = inputs() + filterType("bb_output")
        return if (ns != null) {
            (setOf(ns) + transitiveFanin(ns)).intersect(allStarts)
        } else {
            allStarts
        }
    }

    fun startpoints(ns: Iterable<String>): Set<String> {
        val nsSet = ns.toSet()
        val allStarts = inputs() + filterType("bb_output")
        return if (nsSet.isNotEmpty()) {
            (nsSet + transitiveFanin(nsSet)).intersect(allStarts)
        } else {
            allStarts
        }
    }

    fun endpoints(ns: String? = null): Set<String> {
        val allEnds = outputs() + filterType("bb_input")
        return if (ns != null) {
            (setOf(ns) + transitiveFanout(ns)).intersect(allEnds)
        } else {
            allEnds
        }
    }

    fun reconvergentFanoutNodes(): List<String> {
        val result = mutableListOf<String>()
        for (node in nodes()) {
            val fo = fanout(node).toList()
            if (fo.size > 1) {
                var found = false
                for (i in fo.indices) {
                    val tfi = transitiveFanout(fo[i])
                    for (j in i + 1 until fo.size) {
                        if (tfi.any { it in transitiveFanout(fo[j]) }) {
                            found = true
                            break
                        }
                    }
                    if (found) break
                }
                if (found) result.add(node)
            }
        }
        return result
    }

    fun hasReconvergentFanout(): Boolean = reconvergentFanoutNodes().isNotEmpty()

    fun isCyclic(): Boolean {
        val inDegree = HashMap<String, Int>(size)
        for (n in nodeTypes.keys) {
            inDegree[n] = predecessors[n]?.size ?: 0
        }
        val q = ArrayDeque<String>()
        for ((n, deg) in inDegree) {
            if (deg == 0) q.addLast(n)
        }
        var visited = 0
        while (q.isNotEmpty()) {
            val u = q.removeFirst()
            visited++
            for (v in successors[u].orEmpty()) {
                val next = (inDegree[v] ?: 0) - 1
                inDegree[v] = next
                if (next == 0) q.addLast(v)
            }
        }
        return visited != nodeTypes.size
    }

    fun topoSort(): List<String> {
        val inDegree = HashMap<String, Int>(size)
        for (n in nodeTypes.keys) {
            inDegree[n] = predecessors[n]?.size ?: 0
        }
        val q = ArrayDeque<String>()
        for (n in nodeTypes.keys) {
            if (inDegree[n] == 0) q.addLast(n)
        }
        val order = ArrayList<String>(size)
        while (q.isNotEmpty()) {
            val u = q.removeFirst()
            order.add(u)
            for (v in successors[u].orEmpty()) {
                val next = (inDegree[v] ?: 0) - 1
                inDegree[v] = next
                if (next == 0) q.addLast(v)
            }
        }
        if (order.size != nodeTypes.size) {
            throw IllegalStateException("Circuit contains a cycle; cannot topologically sort.")
        }
        return order
    }

    fun uid(n: String, blocked: Set<String> = emptySet()): String {
        if (n !in this && n !in blocked) return n
        var i = 0
        while ("${n}_$i" in this || "${n}_$i" in blocked) {
            i = if (i < 10) i + 1 else i * 7
        }
        return "${n}_$i"
    }

    fun kcuts(n: String, k: Int, computed: MutableMap<String, List<Set<String>>> = HashMap()): List<Set<String>> {
        computed[n]?.let { return it }
        val fi = fanin(n)
        val cuts: List<Set<String>> = if (fi.isNotEmpty()) {
            val faninCutSets = fi.map { kcuts(it, k, computed) }
            var acc = faninCutSets.first()
            for (idx in 1 until faninCutSets.size) {
                val nextSet = faninCutSets[idx]
                val merged = mutableListOf<Set<String>>()
                for (aCut in acc) {
                    for (bCut in nextSet) {
                        val m = aCut + bCut
                        if (m.size <= k) merged.add(m)
                    }
                }
                acc = merged
            }
            acc + listOf(setOf(n))
        } else {
            listOf(setOf(n))
        }
        computed[n] = cuts
        return cuts
    }

    fun removeUnloaded(inputs: Boolean = false): List<String> {
        val unloaded = ArrayDeque<String>()
        for (n in nodeTypes.keys) {
            if (type(n) != "bb_input" && !isOutput(n) && fanout(n).isEmpty()) {
                unloaded.addLast(n)
            }
        }
        val removed = mutableListOf<String>()
        while (unloaded.isNotEmpty()) {
            val n = unloaded.removeLast()
            if (n !in this) continue
            for (fi in fanin(n)) {
                if (!inputs && type(fi) in listOf("input", "bb_output")) continue
                if (!isOutput(fi) && fanout(fi).size == 1) {
                    unloaded.addLast(fi)
                }
            }
            remove(n)
            removed.add(n)
        }
        return removed
    }

    fun addSubcircuit(
        sc: Circuit,
        instName: String,
        connections: Map<String, String>? = null,
        stripIo: Boolean = true
    ) {
        for (bbName in sc.blackboxes.keys) {
            if ("${instName}_$bbName" in blackboxes) {
                throw IllegalArgumentException("blackbox ${instName}_$bbName already exists.")
            }
        }
        val mapping = LinkedHashMap<String, String>()
        for (n in sc.orderedNodes()) {
            val mapped = "${instName}_$n"
            if (mapped in this) {
                throw IllegalArgumentException("name $n overlaps with $instName subcircuit.")
            }
            mapping[n] = mapped
        }
        val scInputs = sc.inputs()
        val scOutputs = sc.outputs()
        if (connections != null) {
            for (scN in connections.keys) {
                if (scN !in scInputs && scN !in scOutputs) {
                    throw IllegalArgumentException("node $scN not in $instName io")
                }
            }
        }

        for (n in sc.orderedNodes()) {
            val mapped = mapping[n]!!
            nodeTypes[mapped] = sc.type(n)
            nodeOutputs[mapped] = sc.isOutput(n)
            predecessors[mapped] = LinkedHashSet()
            successors[mapped] = LinkedHashSet()
        }
        for ((u, v) in sc.edges()) {
            val mu = mapping[u]!!
            val mv = mapping[v]!!
            successors[mu]!!.add(mv)
            predecessors[mv]!!.add(mu)
        }

        if (stripIo) {
            for (n in scInputs) setType("${instName}_$n", "buf")
            for (n in scOutputs) setOutput("${instName}_$n", false)
        }

        for ((bbName, bb) in sc.blackboxes) {
            blackboxes["${instName}_$bbName"] = bb
        }

        if (connections != null) {
            for ((scN, ns) in connections) {
                if (scN in scInputs) {
                    connect(ns, "${instName}_$scN")
                } else if (scN in scOutputs) {
                    connect("${instName}_$scN", ns)
                }
            }
        }
    }

    fun addBlackbox(
        blackbox: BlackBox,
        instName: String,
        connections: Map<String, String>? = null
    ) {
        if (instName in blackboxes) {
            throw IllegalArgumentException("blackbox $instName already exists.")
        }
        blackboxes[instName] = blackbox
        for (n in blackbox.inputs) add("$instName.$n", "bb_input")
        for (n in blackbox.outputs) add("$instName.$n", "bb_output")

        if (connections != null) {
            for ((bbN, ns) in connections) {
                when (bbN) {
                    in blackbox.inputs -> connect(ns, "$instName.$bbN")
                    in blackbox.outputs -> connect("$instName.$bbN", ns)
                    else -> throw IllegalArgumentException("node $bbN not defined for blackbox $instName")
                }
            }
        }
    }

    fun fillBlackbox(instName: String, c: Circuit) {
        val bb = blackboxes[instName] ?: throw IllegalArgumentException("blackbox $instName does not exist.")
        for (bbName in c.blackboxes.keys) {
            if ("${instName}_$bbName" in blackboxes) {
                throw IllegalArgumentException("blackbox ${instName}_$bbName already exists.")
            }
        }
        if (c.inputs() != bb.inputs) {
            throw IllegalArgumentException("circuit inputs do not match $instName blackbox.")
        }
        if (c.outputs() != bb.outputs) {
            throw IllegalArgumentException("circuit outputs do not match $instName blackbox.")
        }
        val mapping = LinkedHashMap<String, String>()
        for (n in c.orderedNodes()) {
            val mapped = "${instName}_$n"
            if (mapped in this) {
                throw IllegalArgumentException("name overlap with $instName blackbox.")
            }
            mapping[n] = mapped
        }
        relabel(bb.io().associate { "$instName.$it" to "${instName}_$it" })

        for (n in c.orderedNodes()) {
            val mapped = mapping[n]!!
            nodeTypes[mapped] = c.type(n)
            nodeOutputs[mapped] = c.isOutput(n)
            predecessors.putIfAbsent(mapped, LinkedHashSet())
            successors.putIfAbsent(mapped, LinkedHashSet())
        }
        for ((u, v) in c.edges()) {
            val mu = mapping[u]!!
            val mv = mapping[v]!!
            successors.getOrPut(mu) { LinkedHashSet() }.add(mv)
            predecessors.getOrPut(mv) { LinkedHashSet() }.add(mu)
        }
        for (n in bb.inputs) setType("${instName}_$n", "buf")
        for (n in bb.outputs) setOutput("${instName}_$n", false)

        blackboxes.remove(instName)
        for ((bbName, subBb) in c.blackboxes) {
            blackboxes["${instName}_$bbName"] = subBb
        }
    }
}
