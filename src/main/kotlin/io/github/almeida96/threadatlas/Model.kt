package io.github.almeida96.threadatlas

internal data class Frame(val text: String, val line: Int)
internal data class HeldLock(val id: String, val kind: String, val line: Int)
internal data class WaitLock(val id: String, val kind: String, val line: Int)
internal data class DumpThread(val id: String, val name: String, val state: String, val headerLine: Int,
    val frames: List<Frame>, val held: List<HeldLock>, val waiting: WaitLock?)
internal data class Dump(val name: String, val sha256: String, val bytes: Int, val timestamp: String?,
    val threads: List<DumpThread>, val notes: List<String>)
internal data class Edge(val waiter: String, val owner: String, val lock: String, val kind: String,
    val waitLine: Int, val ownerLine: Int)
internal data class Gap(val thread: String, val lock: String, val reason: String)
internal data class Cycle(val id: String, val kind: String, val threads: List<String>, val edges: List<Edge>)
internal data class Blocker(val thread: String, val directWaiters: Int, val downstreamWaiters: Int, val inCycle: Boolean)
internal data class Snapshot(val index: Int, val source: Dump, val verdict: String, val edges: List<Edge>,
    val cycles: List<Cycle>, val blockers: List<Blocker>, val unresolved: List<Gap>)
internal data class StackGroup(val id: String, val state: String, val frames: List<String>, val counts: List<Int>)
internal data class Report(val formatVersion: Int, val verdict: String, val snapshots: List<Snapshot>, val stacks: List<StackGroup>)
