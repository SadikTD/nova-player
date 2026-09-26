package com.sadik.novaplayer

import com.sadik.novaplayer.core.SubtitleTiming.Cue
import kotlin.math.abs

/** How on-time lines appear: per 40-line section, the median gap between a line appearing and
 *  the voice starting, compared with the studio convention (0.12 s ahead). */
object LeadMetric {
    class Totals { var sections = 0; var off = 0; var dev = 0.0
        override fun toString() = if (sections == 0) "-" else "sections %d  mean|dev| %.3fs  off>.25s %d%%".format(sections, dev / sections, off * 100 / sections) }
    fun add(t: Totals, cues: List<Cue>, speech: List<Cue>) {
        val onsets = mutableListOf<Double>(); var last = Double.NEGATIVE_INFINITY
        for (c in speech.sortedBy { it.start }) { if (c.start - last >= .25 && c.end - c.start >= .2) onsets += c.start; last = maxOf(last, c.end) }
        for (section in cues.sortedBy { it.start }.chunked(40)) {
            val gaps = section.mapNotNull { c -> onsets.filter { abs(it - c.start) <= 1.0 }.minByOrNull { abs(it - c.start) }?.minus(c.start) }.sorted()
            if (gaps.size < 10) continue
            val d = abs(gaps[gaps.size / 2] - .12); t.sections++; t.dev += d; if (d > .25) t.off++
        }
    }
}
