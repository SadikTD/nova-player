package com.sadik.novaplayer

import com.sadik.novaplayer.core.SubtitleTiming
import com.sadik.novaplayer.core.SubtitleTiming.Cue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Real downloaded subtitles against the studio subtitle embedded in the same video. Skipped
 * unless NOVA_SYNC_REAL (folder of `<ep><tag>.srt` downloads) and NOVA_SYNC_TRUTH (folder of
 * `<ep>.truth.srt` + `<ep>.speech3.txt`) are set. Lines are paired by their words, so the two
 * files may split dialogue differently.
 */
class RealBenchTest {
    private fun words(text: String) = text.replace(Regex("<[^>]*>|\\{[^}]*}|\\[[^]]*]|\\([^)]*\\)"), " ")
        .lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    @Test fun real() {
        val real = System.getenv("NOVA_SYNC_REAL")?.let(::File); val truthDir = System.getenv("NOVA_SYNC_TRUTH")?.let(::File)
        System.getenv("NOVA_SYNC_WINDOW")?.let { SubtitleAlign.localWindow = it.toDouble() }
        System.getenv("NOVA_SYNC_ONSET")?.let { SubtitleAlign.onsetWeight = it.toDouble() }
        System.getenv("NOVA_SYNC_LEAD")?.let { SubtitleAlign.LEAD = it.toDouble() }
        assumeTrue(real?.isDirectory == true && truthDir?.isDirectory == true)
        val modes = (System.getenv("NOVA_SYNC_MODES") ?: "smart").split(',')
        val totals = sortedMapOf<String, MutableList<Double>>()
        val leads = sortedMapOf<String, LeadMetric.Totals>()
        for (file in real!!.listFiles { f -> f.name.endsWith(".srt") && !f.name.contains(".out") }!!.sorted()) {
            val ep = file.name.take(3)
            val truth = SubtitleTiming.cues(File(truthDir, "$ep.truth.srt").readText())
            val index = truth.groupBy { words(it.text) }.filter { (k, v) -> k.length >= 12 && v.size == 1 }.mapValues { it.value[0].start }
            val speech = File(truthDir, "$ep.speech3.txt").readLines().filter { it.isNotBlank() }
                .map { it.trim().split(' ').let { p -> Cue(p[0].toDouble(), p[1].toDouble()) } }
            val before = SubtitleTiming.cues(SubtitleTiming.repairSrt(file.readText()))
            fun errors(cues: List<Cue>) = cues.mapNotNull { c -> index[words(c.text)]?.let { c.start - it } }
            val line = StringBuilder("%-8s before %s".format(file.nameWithoutExtension, stats(errors(before))))
            for (mode in modes) {
                val fit = SubtitleAlign.fit(before, speech, mode)
                val err = errors(fit.cues)
                totals.getOrPut(mode) { mutableListOf() }.addAll(err)
                LeadMetric.add(leads.getOrPut(mode) { LeadMetric.Totals() }, fit.cues, speech)
                line.append(" | $mode " + stats(err) + " conf %.2f".format(fit.confidence))
                if (System.getenv("NOVA_SYNC_TRACE") != null) println("   trace ${file.nameWithoutExtension} $mode: " + fit.cues.chunked(60).joinToString(" ") { ch ->
                    val e = errors(ch).sorted(); if (e.isEmpty()) "-" else "%.0f:%+.2f".format(ch[0].start / 60, e[e.size / 2]) })
            }
            println(line)
        }
        totals.forEach { (k, v) -> println("TOTAL %-8s %s".format(k, stats(v))) }
        leads.forEach { (k, v) -> println("LEAD real %-8s ".format(k) + v) }
    }

    private fun stats(err: List<Double>): String {
        if (err.isEmpty()) return "no matches"
        val a = err.map { abs(it) }.sorted(); val n = a.size
        return "n %3d bias %+.2f med %.2f ≤.1 %3d%% ≤.2 %3d%% ≤.3 %3d%% ≤.5 %3d%% >1 %3d%%".format(n, err.sorted()[n / 2], a[n / 2],
            a.count { it <= .1 } * 100 / n, a.count { it <= .2 } * 100 / n, a.count { it <= .3 } * 100 / n, a.count { it <= .5 } * 100 / n, a.count { it > 1 } * 100 / n)
    }
}
