# CircuitGraph (Android)

Native Kotlin & Jetpack Compose rewrite of the [CircuitGraph](https://github.com/MoelMegwari/circuitgraph) hardware circuit graph library for Android.

## Core Features

- **Directed Circuit Graph Engine (`core/Circuit.kt`)**: Full node/edge manipulation, transitive fanin/fanout cones, depth/path queries, reconvergent fanout detection, cycle checking, topological sorting, K-cut enumeration, subcircuit insertion, and sequential `BlackBox` pin mapping.
- **Interactive DAG Workbench & Live Simulator (`ui/components/CircuitGraphCanvas.kt`, `ui/screens/WorkbenchScreen.kt`)**: Hardware-accelerated Compose pan/zoom levelized circuit canvas with curved wires, color-coded gate types, node inspector, graph editing (`Add Node`, `Connect`, `Prune Unloaded`, `Undo`), and live 3-valued (`0` / `1` / `X`) topological logic simulation.
- **Benchmark Library & Parametric Builders (`core/Logic.kt`, `ui/screens/LibraryScreen.kt`)**: 23 bundled Verilog & Bench benchmarks (`c17`, `s27`, `mux_2`/`4`, `banyan_8`/`16`, ISCAS-85 `c432`–`c1908`, `adder`, `b17_C`), Storage Access Framework `.v`/`.bench` file import, local circuit persistence, and parametric generators (`halfAdder`, `fullAdder`, `adder`, `mux`, `popcount`).
- **Pure-Kotlin SAT, #SAT & Property Analysis (`core/Sat.kt`, `core/Props.kt`, `ui/screens/AnalysisScreen.kt`)**: Tseitin CNF encoding, iterative DPLL SAT solver with node assumptions, exact `#SAT` blocking-clause model counting (plus Monte Carlo fallback for >12 startpoints), signal probability, observability sensitization, input influence, equivalence checking miters, and structural lint diagnostics.
- **Graph Transforms & Netlist Serialization (`core/Tx.kt`, `core/Io.kt`, `ui/screens/TransformsScreen.kt`)**: `limitFanin`, `limitFanout`, pure-Kotlin `toAig` (And-Inverter Graph mapping), `ternary` X-simulation transform, `sequentialUnroll`, `acyclicUnroll`, `insertRegisters`, `stripBlackboxes`, plus bidirectional Gate/Behavioral Verilog and Bench serialization and SAF export.

## Build & Test

- Compile Debug APK: `gradle assembleDebug`
- Run JVM Unit Tests: `gradle :app:testDebugUnitTest`
