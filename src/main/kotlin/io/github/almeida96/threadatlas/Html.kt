package io.github.almeida96.threadatlas

internal fun escape(s: String) = s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
internal fun render(report: Report): String {
    val sections = report.snapshots.joinToString("") { s ->
        val threads = s.source.threads.associateBy { it.id }
        val waits = s.edges.associateBy { it.waiter }
        fun link(id: String) = "<a href='#s${s.index}t$id'>${escape(threads.getValue(id).name)} <small>#$id</small></a>"
        val cycles = s.cycles.joinToString("") { c ->
            "<article class='cycle'><b>${c.kind} · ${c.threads.size} threads</b><p>" +
            (c.threads + c.threads.first()).joinToString(" → ") { link(it) } +
            "</p><table><tr><th>Waiting thread</th><th>Lock</th><th>Owner</th><th>Evidence lines</th></tr>" +
            c.edges.joinToString("") { e -> "<tr><td>${link(e.waiter)}</td><td><code>${e.lock}</code><br>${e.kind}</td><td>${link(e.owner)}</td><td>${e.waitLine} → ${e.ownerLine}</td></tr>" } + "</table></article>"
        }
        val blockers = if (s.blockers.isEmpty()) "<p>No owner-linked waits observed.</p>" else "<table><tr><th>Lock holder</th><th>Direct waiters</th><th>Downstream waiters</th><th>Cycle member</th></tr>" +
            s.blockers.take(20).joinToString("") { "<tr><td>${link(it.thread)}</td><td>${it.directWaiters}</td><td>${it.downstreamWaiters}</td><td>${if (it.inCycle) "Yes" else "No"}</td></tr>" } + "</table>"
        val rows = s.source.threads.joinToString("") { t ->
            val edge = waits[t.id]
            "<details id='s${s.index}t${t.id}'><summary><b>${escape(t.name)}</b> <small>#${t.id} · ${t.state} · line ${t.headerLine}</small></summary>" +
            (if (edge == null) "" else "<p>Waiting for ${link(edge.owner)} on <code>${edge.lock}</code> · evidence lines ${edge.waitLine} / ${edge.ownerLine}</p>") +
            "<pre>" + t.frames.joinToString("\n") { "${it.line.toString().padStart(5)}  ${escape(it.text)}" } + "</pre></details>"
        }
        val gaps = if (s.unresolved.isEmpty()) "" else "<p class='note'>${s.unresolved.size} waits have unresolved or inconsistent ownership. Inspect the JSON for their thread and lock IDs.</p>"
        """<section><div class="section-title"><h2>Capture ${s.index}</h2><span>${s.verdict}</span></div>
<p>${escape(s.source.name)} · ${s.source.threads.size} platform threads · ${s.edges.size} owner-linked waits<br><small>SHA-256: <code>${s.source.sha256}</code></small></p>
$cycles<h3>Lock holders</h3>$blockers$gaps<details class="all"><summary>Thread stacks and source lines (${s.source.threads.size})</summary>$rows</details>
<p class="note">${s.source.notes.joinToString(" ") { escape(it) }}</p></section>"""
    }
    val trends = report.stacks.take(30).joinToString("") { g ->
        "<tr><td>${g.state}</td><td><code>${escape(g.frames.firstOrNull() ?: "(no Java frame)")}</code><details><summary>Stack signature</summary><pre>${escape(g.frames.joinToString("\n"))}</pre></details></td>" +
            g.counts.joinToString("") { "<td>$it</td>" } + "</tr>"
    }
    val headline = when (report.verdict) { "DEADLOCK" -> "Lock cycles explain the stalled threads"; "CONTENTION" -> "Threads are waiting behind lock holders"; else -> "No lock cycle observed in these captures" }
    return """<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ThreadAtlas · ${report.verdict}</title><style>
*{box-sizing:border-box}body{margin:0;background:#f3f0e9;color:#232827;font:15px/1.55 system-ui,sans-serif}main{max-width:1120px;margin:auto;padding:36px 40px}header{display:flex;justify-content:space-between;border-bottom:2px solid #314542;padding-bottom:14px}h1{font-size:clamp(30px,4vw,49px);line-height:1.12;letter-spacing:-.035em;max-width:800px;margin:16px 0}h2{margin:0;font-size:27px}h3{margin:26px 0 10px}small,.note{color:#53645f}code,pre{font-family:ui-monospace,Consolas,monospace;font-size:12px}code{overflow-wrap:anywhere}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f0f2ed;padding:12px}section{border-top:1px solid #bbc6bd;margin-top:30px;padding-top:24px}.section-title{display:flex;justify-content:space-between;align-items:center}.section-title span,.verdict{font-size:12px;font-weight:700;letter-spacing:.07em}.verdict{color:#9f3424;margin-top:32px}.cycle{background:#fffaf5;border-left:4px solid #b74c2f;padding:15px 20px;margin:14px 0}.cycle p{font-size:17px}a{color:#224e43;text-decoration-thickness:1px;text-underline-offset:3px}table{width:100%;border-collapse:collapse;font-size:14px}th,td{text-align:left;border-bottom:1px solid #dce1d7;padding:9px 8px;vertical-align:top}th{background:#e5ebe2;font-weight:600}details{padding:10px 0}summary{cursor:pointer}details.all{border:1px solid #c2cec2;padding:12px 18px;margin-top:25px;background:#fff}details:target{background:#fff2c1}footer{margin-top:35px;border-top:2px solid #314542;padding-top:14px}.table{overflow-x:auto}@media(max-width:650px){main{padding:24px 16px}table{font-size:12px}.cycle{padding:12px}}
</style><main><header><b>ThreadAtlas</b><span>JVM contention evidence</span></header>
<div class="verdict">${report.verdict}</div><h1>$headline</h1><p>${report.snapshots.size} capture(s) · ${report.snapshots.sumOf { it.cycles.count { c -> c.kind == "DEADLOCK" } }} deadlock cycle observations</p>
$sections<section><h2>Repeated stack signatures</h2><p>Top 30 groups by capture coverage, then sample count. Groups match state and the first eight frames. Repeated samples do not prove continuous blocking or CPU use.</p>
<div class="table"><table><tr><th>State</th><th>Top frame</th>${report.snapshots.joinToString("") { "<th>Capture ${it.index}</th>" }}</tr>$trends</table></div></section>
<footer>Analysis uses only owner relationships inside each individual capture. Object addresses and thread IDs are never matched across captures. A capture without a cycle is not a health check.</footer></main></html>"""
}
