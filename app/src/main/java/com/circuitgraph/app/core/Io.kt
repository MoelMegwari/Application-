package com.circuitgraph.app.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Verilog and Bench parsers/exporters and persistent CircuitStorage,
 * ported from `circuitgraph/io.py` and `circuitgraph/parsing/`.
 */
object CircuitIo {

    val genericFlop = BlackBox("ff", listOf("clk", "d"), listOf("q"))

    val genusFlops = listOf(
        BlackBox("ff", listOf("CK", "D"), listOf("Q")),
        BlackBox("flopd", listOf("CK", "D"), listOf("Q")),
        BlackBox("fflopd", listOf("CK", "D"), listOf("Q")),
        BlackBox("flopdrs", listOf("CK", "D", "R", "S"), listOf("Q")),
        BlackBox("fflopdrs", listOf("CK", "D", "R", "S"), listOf("Q"))
    )

    val dcFlops = listOf(
        BlackBox("GTECH_FD1", listOf("CP", "D"), listOf("Q", "QN")),
        BlackBox("GTECH_FD2", listOf("CP", "CD", "D"), listOf("Q", "QN")),
        BlackBox("GTECH_FD3", listOf("CP", "CD", "SD", "D"), listOf("Q", "QN"))
    )

    val defaultBlackboxes: List<BlackBox> = listOf(genericFlop) + genusFlops + dcFlops

    fun fromText(
        content: String,
        name: String? = null,
        formatHint: String? = null,
        blackboxes: List<BlackBox> = defaultBlackboxes
    ): Circuit {
        val trimmed = content.trim()
        val isBench = formatHint.equals("bench", true) ||
            (!formatHint.equals("verilog", true) &&
                (Regex("""(?im)^\s*INPUT\s*\(""").containsMatchIn(trimmed) ||
                    Regex("""(?im)^\s*OUTPUT\s*\(""").containsMatchIn(trimmed)))

        return if (isBench) {
            benchToCircuit(content, name ?: "bench_circuit")
        } else {
            verilogToCircuit(content, name, inferModuleName = true, blackboxes = blackboxes)
        }
    }

    fun benchToCircuit(netlist: String, name: String = "circuit"): Circuit {
        val c = Circuit(name = name)
        val dff = BlackBox("dff", listOf("D"), listOf("Q"))

        val cleanedLines = netlist.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }

        val fullText = cleanedLines.joinToString("\n")

        val inRegex = Regex("""(?:INPUT|input)\s*\(\s*([a-zA-Z_][a-zA-Z\d_]*)\s*\)""")
        for (m in inRegex.findAll(fullText)) {
            c.add(m.groupValues[1], "input", allowRedefinition = true)
        }

        val gateRegex = Regex("""([a-zA-Z_][a-zA-Z\d_]*)\s*=\s*([a-zA-Z]+)\s*\(\s*([^)]+)\s*\)""")
        for (m in gateRegex.findAll(fullText)) {
            val net = m.groupValues[1].trim()
            val rawGate = m.groupValues[2].trim().lowercase()
            val inputsRaw = m.groupValues[3]
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            if (rawGate == "dff") {
                c.add(net, "buf", allowRedefinition = true)
                val dNet = inputsRaw.first()
                if (dNet !in c) c.add(dNet, "buf")
                c.addBlackbox(dff, "${net}_dff", mapOf("D" to dNet, "Q" to net))
            } else {
                val gate = if (rawGate == "buff") "buf" else rawGate
                c.add(
                    n = net,
                    nodeType = gate,
                    fanin = inputsRaw,
                    addConnectedNodes = true,
                    allowRedefinition = true
                )
            }
        }

        val outRegex = Regex("""(?:OUTPUT|output)\s*\(\s*([a-zA-Z_][a-zA-Z\d_]*)\s*\)""")
        for (m in outRegex.findAll(fullText)) {
            val outNet = m.groupValues[1].trim()
            if (outNet !in c) c.add(outNet, "buf")
            c.setOutput(outNet, true)
        }

        return c
    }

    fun verilogToCircuit(
        netlist: String,
        name: String? = null,
        inferModuleName: Boolean = true,
        blackboxes: List<BlackBox> = defaultBlackboxes
    ): Circuit {
        val stripped = stripVerilogComments(netlist)
        val moduleRegex = if (name != null && !inferModuleName) {
            Regex("""module\s+${Regex.escape(name)}\s*\((.*?)\)\s*;(.*?)endmodule""", setOf(RegexOption.DOT_MATCHES_ALL))
        } else {
            Regex("""module\s+([^\s(]+)\s*\((.*?)\)\s*;(.*?)endmodule""", setOf(RegexOption.DOT_MATCHES_ALL))
        }

        val match = moduleRegex.find(stripped)
            ?: throw IllegalArgumentException("Could not read Verilog netlist: no module definition found")

        val moduleName = if (name != null && !inferModuleName) name else sanitizeIdent(match.groupValues[1])
        val body = if (name != null && !inferModuleName) match.groupValues[2] else match.groupValues[3]

        val c = Circuit(name = moduleName)
        val tie0 = c.add("tie_0", "0")
        val tie1 = c.add("tie_1", "1")
        val tieX = c.add("tie_x", "x")
        val outputs = LinkedHashSet<String>()
        val bbMap = blackboxes.associateBy { it.name }

        val statements = splitStatements(body)
        for (stmt in statements) {
            val trimmed = stmt.trim()
            if (trimmed.isEmpty()) continue

            when {
                trimmed.startsWith("input ") || trimmed.startsWith("input\t") || trimmed.startsWith("input\n") -> {
                    val vars = parseSignalList(trimmed.removePrefix("input"))
                    for (v in vars) {
                        c.add(v, "input", allowRedefinition = true)
                    }
                }
                trimmed.startsWith("output ") || trimmed.startsWith("output\t") || trimmed.startsWith("output\n") -> {
                    val vars = parseSignalList(trimmed.removePrefix("output"))
                    outputs.addAll(vars)
                }
                trimmed.startsWith("wire ") || trimmed.startsWith("wire\t") || trimmed.startsWith("wire\n") -> {
                    // Wire declarations don't need explicit nodes until connected
                }
                trimmed.startsWith("assign ") || trimmed.startsWith("assign\t") -> {
                    val assignBody = trimmed.removePrefix("assign").trim()
                    val eqIdx = assignBody.indexOf('=')
                    if (eqIdx < 0) throw IllegalArgumentException("Invalid assign statement: $trimmed")
                    val lhs = sanitizeIdent(assignBody.substring(0, eqIdx).trim())
                    val rhs = assignBody.substring(eqIdx + 1).trim()
                    if (lhs !in setOf(tie0, tie1, tieX)) {
                        val exprNode = ExprParser(rhs, c, tie0, tie1, tieX).parse()
                        if (exprNode == tie0 || exprNode == tie1 || exprNode == tieX) {
                            c.add(lhs, "buf", fanin = exprNode, addConnectedNodes = true, allowRedefinition = true)
                        } else if (exprNode.startsWith("__expr_")) {
                            val exprType = c.type(exprNode)
                            val exprFanin = c.fanin(exprNode).toList()
                            c.remove(exprNode)
                            c.add(lhs, exprType, fanin = exprFanin, addConnectedNodes = true, allowRedefinition = true)
                        } else {
                            c.add(lhs, "buf", fanin = exprNode, addConnectedNodes = true, allowRedefinition = true)
                        }
                    }
                }
                else -> {
                    parseInstantiation(trimmed, c, bbMap, tie0, tie1, tieX)
                }
            }
        }

        for (o in outputs) {
            if (o !in c) c.add(o, "buf")
            c.setOutput(o, true)
        }

        if (c.fanout(tie0).isEmpty()) c.remove(tie0)
        if (c.fanout(tie1).isEmpty()) c.remove(tie1)
        if (c.fanout(tieX).isEmpty()) c.remove(tieX)

        return c
    }

    private fun stripVerilogComments(src: String): String {
        val noBlock = src.replace(Regex("""/\*.*?\*/""", setOf(RegexOption.DOT_MATCHES_ALL)), " ")
        return noBlock.replace(Regex("""//[^\n]*"""), " ")
    }

    private fun splitStatements(body: String): List<String> {
        val list = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < body.length) {
            val ch = body[i]
            if (ch == '\\') {
                // Escaped identifier runs until whitespace
                while (i < body.length && !body[i].isWhitespace()) {
                    sb.append(body[i])
                    i++
                }
                continue
            }
            if (ch == ';') {
                list.add(sb.toString())
                sb.clear()
            } else {
                sb.append(ch)
            }
            i++
        }
        if (sb.isNotBlank()) list.add(sb.toString())
        return list
    }

    private fun parseSignalList(raw: String): List<String> {
        // Strip optional bus range like [7:0]
        val cleaned = raw.replace(Regex("""\[[^\]]+\]"""), " ")
        return cleaned.split(',')
            .map { sanitizeIdent(it) }
            .filter { it.isNotEmpty() }
    }

    private fun sanitizeIdent(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        return if (t.startsWith("\\")) {
            t.substring(1).trim().replace(Regex("""[^a-zA-Z0-9_]"""), "_")
        } else {
            t
        }
    }

    private fun resolveNet(raw: String, tie0: String, tie1: String, tieX: String): String {
        val t = raw.trim()
        return when (t) {
            "1'b0", "1'h0", "1'd0", "0" -> tie0
            "1'b1", "1'h1", "1'd1", "1" -> tie1
            "1'bx", "1'bX", "1'hx", "x", "X" -> tieX
            else -> sanitizeIdent(t)
        }
    }

    private fun parseInstantiation(
        stmt: String,
        c: Circuit,
        bbMap: Map<String, BlackBox>,
        tie0: String,
        tie1: String,
        tieX: String
    ) {
        val parenIdx = stmt.indexOf('(')
        val lastParenIdx = stmt.lastIndexOf(')')
        if (parenIdx < 0 || lastParenIdx < parenIdx) return

        val headerTokens = stmt.substring(0, parenIdx).trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (headerTokens.isEmpty()) return
        val modName = headerTokens[0]
        val instName = if (headerTokens.size >= 2) sanitizeIdent(headerTokens[1]) else c.uid("${modName}_inst")
        val argsStr = stmt.substring(parenIdx + 1, lastParenIdx).trim()

        if (modName in GateTypes.primitiveGates) {
            val nets = argsStr.split(',').map { resolveNet(it, tie0, tie1, tieX) }.filter { it.isNotEmpty() }
            if (nets.isEmpty()) return
            c.add(
                n = nets[0],
                nodeType = modName,
                fanin = nets.drop(1),
                addConnectedNodes = true,
                allowRedefinition = true
            )
        } else {
            val bb = bbMap[modName] ?: throw IllegalArgumentException("Unknown gate or blackbox '$modName'")
            val namedRegex = Regex("""\.\s*([^\s(]+)\s*\(\s*([^)]*)\s*\)""")
            val conns = LinkedHashMap<String, String>()
            for (m in namedRegex.findAll(argsStr)) {
                val port = sanitizeIdent(m.groupValues[1])
                val netRaw = m.groupValues[2].trim()
                if (netRaw.isNotEmpty()) {
                    val net = resolveNet(netRaw, tie0, tie1, tieX)
                    if (net !in c) c.add(net, "buf")
                    conns[port] = net
                }
            }
            for (outPort in bb.outputs) {
                conns[outPort]?.let { if (it !in c) c.add(it, "buf") }
            }
            c.addBlackbox(bb, instName, conns)
        }
    }

    private class ExprParser(
        private val expr: String,
        private val c: Circuit,
        private val tie0: String,
        private val tie1: String,
        private val tieX: String
    ) {
        private var pos = 0

        fun parse(): String = parseTernary()

        private fun skipWs() {
            while (pos < expr.length && expr[pos].isWhitespace()) pos++
        }

        private fun peek(): Char? {
            skipWs()
            return if (pos < expr.length) expr[pos] else null
        }

        private fun parseTernary(): String {
            val cond = parseOr()
            if (peek() == '?') {
                pos++
                val tBranch = parseTernary()
                skipWs()
                if (pos < expr.length && expr[pos] == ':') pos++
                val fBranch = parseTernary()
                val nCond = c.add("__expr_mux_n", "not", fanin = cond, addConnectedNodes = true, uid = true)
                val a0 = c.add("__expr_mux_a0", "and", fanin = listOf(nCond, fBranch), addConnectedNodes = true, uid = true)
                val a1 = c.add("__expr_mux_a1", "and", fanin = listOf(cond, tBranch), addConnectedNodes = true, uid = true)
                return c.add("__expr_mux_o", "or", fanin = listOf(a0, a1), addConnectedNodes = true, uid = true)
            }
            return cond
        }

        private fun parseOr(): String {
            var left = parseXor()
            while (peek() == '|') {
                pos++
                val right = parseXor()
                left = c.add("__expr_or", "or", fanin = listOf(left, right), addConnectedNodes = true, uid = true)
            }
            return left
        }

        private fun parseXor(): String {
            var left = parseAnd()
            while (true) {
                skipWs()
                if (pos + 1 < expr.length && (expr.substring(pos, pos + 2) == "~^" || expr.substring(pos, pos + 2) == "^~")) {
                    pos += 2
                    val right = parseAnd()
                    left = c.add("__expr_xnor", "xnor", fanin = listOf(left, right), addConnectedNodes = true, uid = true)
                } else if (peek() == '^') {
                    pos++
                    val right = parseAnd()
                    left = c.add("__expr_xor", "xor", fanin = listOf(left, right), addConnectedNodes = true, uid = true)
                } else break
            }
            return left
        }

        private fun parseAnd(): String {
            var left = parseUnary()
            while (peek() == '&') {
                pos++
                val right = parseUnary()
                left = c.add("__expr_and", "and", fanin = listOf(left, right), addConnectedNodes = true, uid = true)
            }
            return left
        }

        private fun parseUnary(): String {
            skipWs()
            if (pos < expr.length && (expr[pos] == '~' || expr[pos] == '!')) {
                pos++
                val inner = parseUnary()
                return c.add("__expr_not", "not", fanin = inner, addConnectedNodes = true, uid = true)
            }
            return parsePrimary()
        }

        private fun parsePrimary(): String {
            skipWs()
            if (pos < expr.length && expr[pos] == '(') {
                pos++
                val inner = parseTernary()
                skipWs()
                if (pos < expr.length && expr[pos] == ')') pos++
                return inner
            }
            val start = pos
            if (pos < expr.length && expr[pos] == '\\') {
                while (pos < expr.length && !expr[pos].isWhitespace()) pos++
            } else {
                while (pos < expr.length && (expr[pos].isLetterOrDigit() || expr[pos] == '_' || expr[pos] == '\'')) {
                    pos++
                }
            }
            val tok = expr.substring(start, pos).trim()
            return resolveNet(tok, tie0, tie1, tieX)
        }
    }

    fun circuitToVerilog(c: Circuit, behavioral: Boolean = false): String {
        val copy = c.copy()
        val inputs = copy.inputs().toList()
        val outputs = copy.outputs().toList()
        val insts = mutableListOf<String>()
        val wires = mutableListOf<String>()

        for ((name, bb) in copy.blackboxes) {
            val ioList = mutableListOf<String>()
            for (n in bb.inputs) {
                val driver = copy.fanin("$name.$n").firstOrNull()
                ioList.add(if (driver != null) ".$n($driver)" else ".$n()")
            }
            for (n in bb.outputs) {
                val driven = copy.fanout("$name.$n").firstOrNull()
                if (driven != null) {
                    copy.disconnect("$name.$n", driven)
                    ioList.add(".$n($driven)")
                } else {
                    ioList.add(".$n()")
                }
            }
            insts.add("${bb.name} $name (${ioList.joinToString(", ")})")
        }

        for (n in copy.orderedNodes()) {
            val t = copy.type(n)
            when (t) {
                in GateTypes.primitiveGates -> {
                    wires.add(n)
                    val fanin = copy.fanin(n).toList()
                    if (fanin.isEmpty()) continue
                    if (behavioral) {
                        when (t) {
                            "buf" -> insts.add("assign $n = ${fanin[0]}")
                            "not" -> insts.add("assign $n = ~${fanin[0]}")
                            else -> {
                                val sym = when (t) {
                                    "xor", "xnor" -> "^"
                                    "and", "nand" -> "&"
                                    else -> "|"
                                }
                                val expr = fanin.joinToString(" $sym ")
                                if (t in listOf("xnor", "nor", "nand")) {
                                    insts.add("assign $n = ~($expr)")
                                } else {
                                    insts.add("assign $n = $expr")
                                }
                            }
                        }
                    } else {
                        val gateName = copy.uid("g_${insts.size}")
                        insts.add("$t $gateName($n, ${fanin.joinToString(", ")})")
                    }
                }
                "0", "1", "x" -> {
                    insts.add("assign $n = 1'b$t")
                    wires.add(n)
                }
                "input", "bb_input", "bb_output" -> Unit
            }
        }

        val sb = StringBuilder()
        sb.append("module ${copy.name} (").append((inputs + outputs).joinToString(", ")).append(");\n")
        for (inp in inputs) sb.append("  input $inp;\n")
        if (inputs.isNotEmpty()) sb.append("\n")
        for (out in outputs) sb.append("  output $out;\n")
        if (outputs.isNotEmpty()) sb.append("\n")
        for (w in wires) sb.append("  wire $w;\n")
        if (wires.isNotEmpty()) sb.append("\n")
        for (inst in insts) sb.append("  $inst;\n")
        sb.append("endmodule\n")
        return sb.toString()
    }

    fun circuitToBench(c: Circuit): String {
        if (c.blackboxes.isNotEmpty()) {
            throw IllegalArgumentException("Bench format does not support blackboxes: ${c.name}")
        }
        val inputs = c.inputs()
        val constInp = inputs.firstOrNull() ?: "const_ref"
        val insts = mutableListOf<String>()
        for (n in c.orderedNodes()) {
            if (n in inputs) continue
            val t = c.type(n)
            when (t) {
                in GateTypes.primitiveGates -> {
                    val fi = c.fanin(n).joinToString(", ")
                    insts.add("$n = ${t.uppercase()}($fi)")
                }
                "0" -> insts.add("$n = XOR($constInp, $constInp)")
                "1" -> insts.add("$n = XNOR($constInp, $constInp)")
            }
        }

        val sb = StringBuilder()
        sb.append("# ${c.name}\n")
        for (inp in inputs) sb.append("INPUT($inp)\n")
        sb.append("\n")
        for (out in c.outputs()) sb.append("OUTPUT($out)\n")
        sb.append("\n")
        sb.append(insts.joinToString("\n"))
        sb.append("\n")
        return sb.toString()
    }
}

@Serializable
data class SavedCircuitDto(
    val id: String,
    val name: String,
    val format: String,
    val sourceText: String,
    val nodeCount: Int,
    val edgeCount: Int,
    val savedAtMs: Long
)

data class BundledNetlistInfo(
    val fileName: String,
    val displayName: String,
    val format: String,
    val category: String
)

class CircuitRepository(private val context: Context) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val savedDir: File by lazy {
        File(context.filesDir, "saved_circuits").apply { mkdirs() }
    }

    fun listBundledNetlists(): List<BundledNetlistInfo> {
        val files = context.assets.list("netlists")?.sorted() ?: emptyList()
        return files.map { f ->
            val fmt = if (f.endsWith(".bench")) "Bench" else "Verilog"
            val stem = f.substringBeforeLast('.')
            val cat = when {
                stem.startsWith("c17") || stem.startsWith("mux") || stem == "switch" -> "Starter & Mux"
                stem.startsWith("s27") -> "Sequential (DFF)"
                stem.startsWith("c") && stem.drop(1).takeWhile { it.isDigit() }.isNotEmpty() -> "ISCAS-85"
                stem.startsWith("b17") -> "ITC-99"
                stem.startsWith("banyan") -> "Networks"
                else -> "Arithmetic & Control"
            }
            BundledNetlistInfo(f, stem, fmt, cat)
        }
    }

    fun loadBundledNetlist(fileName: String): Circuit {
        val text = context.assets.open("netlists/$fileName").bufferedReader().use { it.readText() }
        val fmt = if (fileName.endsWith(".bench")) "bench" else "verilog"
        val stem = fileName.substringBeforeLast('.')
        return CircuitIo.fromText(text, name = stem, formatHint = fmt)
    }

    fun listSavedCircuits(): List<SavedCircuitDto> {
        return savedDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    json.decodeFromString<SavedCircuitDto>(file.readText())
                } catch (_: Exception) {
                    null
                }
            }
            ?.sortedByDescending { it.savedAtMs }
            ?: emptyList()
    }

    fun saveCircuit(c: Circuit): SavedCircuitDto {
        val id = c.name.replace(Regex("""[^a-zA-Z0-9_-]"""), "_").ifEmpty { "circuit" }
        val verilog = CircuitIo.circuitToVerilog(c)
        val dto = SavedCircuitDto(
            id = id,
            name = c.name,
            format = "Verilog",
            sourceText = verilog,
            nodeCount = c.size,
            edgeCount = c.edges().size,
            savedAtMs = System.currentTimeMillis()
        )
        File(savedDir, "$id.json").writeText(json.encodeToString(dto))
        return dto
    }

    fun loadSavedCircuit(dto: SavedCircuitDto): Circuit {
        return CircuitIo.fromText(dto.sourceText, name = dto.name, formatHint = dto.format)
    }

    fun deleteSavedCircuit(id: String) {
        File(savedDir, "$id.json").delete()
    }
}
