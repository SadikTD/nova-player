package com.sadik.novaplayer

import com.sadik.novaplayer.core.SubtitleTiming
import com.sadik.novaplayer.core.SubtitleTiming.Cue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Accuracy against perfect timing, skipped unless NOVA_SYNC_TRUTH points at a folder of
 * `<ep>.truth.srt` (the studio subtitle embedded in the video) + `<ep>.speech3.txt` (phone VAD).
 * Each truth file is damaged the way real downloads are, then aligned; the report shows the
 * signed bias (+ = subtitles late) and how close lines land.
 */
class TruthBenchTest {
    private val damage = linkedMapOf<String, (Int, Int, Cue) -> Cue>(
        "synced" to { _, _, c -> c },
        "late3.2" to { _, _, c -> c.copy(start = c.start + 3.2, end = c.end + 3.2) },
        "early2.1" to { _, _, c -> c.copy(start = c.start - 2.1, end = c.end - 2.1) },
        "pal" to { _, _, c -> val f = 23.976 / 25; c.copy(start = c.start * f + 1.0, end = c.end * f + 1.0) },
        "drift+jump" to { i, n, c -> val f = 24 / 23.976; val j = if (i > n / 2) 1.6 else 0.4; c.copy(start = c.start * f + j, end = c.end * f + j) },
    )

    @Test fun truth() {
        val dir = System.getenv("NOVA_SYNC_TRUTH")?.let(::File)
        System.getenv("NOVA_SYNC_WINDOW")?.let { SubtitleAlign.localWindow = it.toDouble() }
        System.getenv("NOVA_SYNC_ONSET")?.let { SubtitleAlign.onsetWeight = it.toDouble() }
        System.getenv("NOVA_SYNC_LEAD")?.let { SubtitleAlign.LEAD = it.toDouble() }
        assumeTrue(dir?.isDirectory == true)
        val modes = (System.getenv("NOVA_SYNC_MODES") ?: "smart").split(',')
        val all = sortedMapOf<String, MutableList<Double>>()
        for (truthFile in dir!!.listFiles { f -> f.name.endsWith(".truth.srt") }!!.sorted()) {
            val ep = truthFile.name.substringBefore('.')
            val truth = SubtitleTiming.cues(truthFile.readText()).filter { it.end > it.start }.sortedBy { it.start }
            val speech = File(dir, "$ep.speech3.txt").readLines().filter { it.isNotBlank() }
                .map { it.trim().split(' ').let { p -> Cue(p[0].toDouble(), p[1].toDouble()) } }
            for ((name, hurt) in damage) for (mode in modes) {
                val input = truth.mapIndexed { i, c -> hurt(i, truth.size, c) }.map { if (it.start < 0) it.copy(start = 0.0, end = maxOf(.01, it.end)) else it }
                val fit = SubtitleAlign.fit(input, speech, mode)
                val err = fit.cues.indices.map { fit.cues[it].start - truth[it].start }
                all.getOrPut("$mode $name") { mutableListOf() }.addAll(err)
                println("%s %-10s %-6s %s conf %.2f".format(ep, name, mode, stats(err), fit.confidence))
            }
        }
        all.forEach { (k, v) -> println("TOTAL %-18s %s".format(k, stats(v))) }
        println("TOTAL ALL                " + stats(all.values.flatten()))
    }

    private fun stats(err: List<Double>): String {
        val a = err.map { abs(it) }.sorted(); val n = a.size
        return "bias %+.3f  med %.3f  ≤.1 %3d%%  ≤.2 %3d%%  ≤.3 %3d%%  >1s %3d%%".format(
            err.sorted()[n / 2], a[n / 2], a.count { it <= .1 } * 100 / n, a.count { it <= .2 } * 100 / n, a.count { it <= .3 } * 100 / n, a.count { it > 1 } * 100 / n)
    }
}
