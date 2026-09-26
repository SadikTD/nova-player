package com.sadik.novaplayer

import com.sadik.novaplayer.core.SubtitleTiming
import com.sadik.novaplayer.core.SubtitleTiming.Cue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Real-episode benchmark, skipped unless NOVA_SYNC_BENCH points at a folder of
 * `<name>.orig.srt` + `<name>.alass.srt` (desktop's approved result) + `<ep>.<speech>.txt`
 * (the phone's VAD output, NOVA_SYNC_SPEECH picks which). Reports how far each mode lands
 * from alass, both from the original and from the already-synced file (must stay put).
 */
class SyncBenchTest {
    private val totals = sortedMapOf<String, IntArray>()
    private val leads = sortedMapOf<String, LeadMetric.Totals>()
    @Test fun bench() {
        val dir = System.getenv("NOVA_SYNC_BENCH")?.let(::File)
        System.getenv("NOVA_SYNC_WINDOW")?.let { SubtitleAlign.localWindow = it.toDouble() }
        System.getenv("NOVA_SYNC_ONSET")?.let { SubtitleAlign.onsetWeight = it.toDouble() }
        System.getenv("NOVA_SYNC_LEAD")?.let { SubtitleAlign.LEAD = it.toDouble() }
        assumeTrue(dir?.isDirectory == true)
        val modes = (System.getenv("NOVA_SYNC_MODES") ?: "offset,gentle,smart").split(',')
        val kind = System.getenv("NOVA_SYNC_SPEECH") ?: "speech"
        val penalties = System.getenv("NOVA_SYNC_PENALTY")?.split(',')?.map { it.toDouble() }
        val all = dir!!.listFiles { f -> f.name.endsWith(".orig.srt") }!!.sorted()
        fun speechOf(ep: String) = File(dir, "$ep.$kind.txt").readLines().filter { it.isNotBlank() }
            .map { it.trim().split(' ').let { p -> Cue(p[0].toDouble(), p[1].toDouble()) } }
        // Negative control: another episode's subtitle must never look trustworthy.
        if (System.getenv("NOVA_SYNC_CROSS") != null) for ((k, orig) in all.withIndex()) {
            val other = all[(k + 2) % all.size].name.substringBefore('.')
            if (other == orig.name.substringBefore('.')) continue
            val before = SubtitleTiming.cues(orig.readText()); val speech = speechOf(other)
            for (mode in modes) { val f = SubtitleAlign.fit(before, speech, mode); val a = SubtitleTiming.assess(before, f.cues, speech)
                println("CROSS ${orig.name.substringBefore('.')} sub vs $other audio $mode: ${a.before}→${a.after}%% sections ${a.sections} confidence %.1f".format(f.confidence)) }
        }
        for (orig in all) {
            val name = orig.name.removeSuffix(".orig.srt")
            val truth = SubtitleTiming.cues(File(dir, "$name.alass.srt").readText())
            val speech = File(dir, name.substringBefore('.') + ".$kind.txt").readLines().filter { it.isNotBlank() }
                .map { it.trim().split(' ').let { p -> Cue(p[0].toDouble(), p[1].toDouble()) } }
            for ((label, before) in listOf("orig" to SubtitleTiming.cues(orig.readText()), "synced" to truth)) {
                val (r, o, z) = SubtitleAlign.overall(before, SubtitleAlign.SpeechMap(speech), doubleArrayOf(1.0, 24 / 23.976, 23.976 / 24, 25 / 24.0, 24 / 25.0, 25 / 23.976, 23.976 / 25)) {}
                val line = StringBuilder("%-16s %-6s r=%.4f o=%.2f z=%.1f before %s".format(name, label, r, o, z, stats(before, truth)))
                val lambdas = System.getenv("NOVA_SYNC_LAMBDA")?.split(',')?.map { it.toDouble() } ?: listOf(Double.NaN)
                for (mode in modes) for (p in penalties ?: listOf(SubtitleAlign.penalty(mode))) for (l0 in lambdas) {
                    val l = if (l0.isNaN()) SubtitleAlign.jumpCost(mode) else l0
                    if (mode == "offset" && ((penalties != null && p != penalties[0]) || lambdas.indexOf(l0) > 0)) continue
                    val t0 = System.nanoTime()
                    val after = SubtitleAlign.align(before, speech, mode, splitPenalty = p, jumpCost = l)
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    val a = SubtitleTiming.assess(before, after, speech)
                    if (label == "orig") LeadMetric.add(leads.getOrPut(mode) { LeadMetric.Totals() }, after, speech)
                    totals.getOrPut("$label ${mode.take(3)}$p/$l") { IntArray(4) }.let { t -> after.indices.forEach { i -> val e = abs(after[i].start - truth[i].start); t[0]++; if (e <= .3) t[1]++; if (e <= .6) t[2]++; if (e > 1.5) t[3]++ } }
                    if (System.getenv("NOVA_SYNC_TRACE") != null && label == "orig") println("   trace $name $mode$p/$l: " + after.indices.chunked(40).joinToString(" ") { c -> "%.0f:%+.2f".format(truth[c[0]].start / 60, c.map { after[it].start - truth[it].start }.sorted()[c.size / 2]) })
                    line.append(" | ${mode.take(3)}${if (penalties != null && mode != "offset") "$p/$l" else ""} ${stats(after, truth)} ${a.before}→${a.after}%${if (a.reliable) "" else "?"} ${ms}ms")
                }
                println(line)
            }
        }
    }

    @org.junit.After fun summary() = leads.forEach { (k, v) -> println("LEAD mentalist %-8s ".format(k) + v) }.also { totals.forEach { (k, t) -> println("TOTAL %-26s ≤.3s %3d%%  ≤.6s %3d%%  >1.5s %3d%%".format(k, t[1] * 100 / t[0], t[2] * 100 / t[0], t[3] * 100 / t[0])) } }

    private fun stats(cues: List<Cue>, truth: List<Cue>): String {
        if (cues.size != truth.size) return "count ${cues.size}≠${truth.size}"
        val err = cues.indices.map { abs(cues[it].start - truth[it].start) }.sorted()
        return "med %.2f p90 %.2f ≤.3s %3d%%".format(err[err.size / 2], err[err.size * 9 / 10], err.count { it <= .3 } * 100 / err.size)
    }
}
