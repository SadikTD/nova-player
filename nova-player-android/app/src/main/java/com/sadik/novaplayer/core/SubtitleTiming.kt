package com.sadik.novaplayer.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Desktop subtitle-timing.js port. This assesses timing, not dialogue accuracy. */
object SubtitleTiming {
    data class Cue(val start: Double, val end: Double, val text: String = "")
    data class Overlap(val score: Double, val coverage: Double)
    data class Assessment(
        val lines: Int, val before: Int, val after: Int,
        val medianShift: Double, val sections: Int, val reliable: Boolean,
    )

    private fun clock(value: String): Double {
        val parts = value.trim().replace(',', '.').split(':')
        if (parts.size != 3) return Double.NaN
        val values = parts.map { it.toDoubleOrNull() ?: return Double.NaN }
        return values[0] * 3600 + values[1] * 60 + values[2]
    }

    fun cues(source: String, extension: String = ".srt"): List<Cue> {
        val text = source.removePrefix("\uFEFF").replace("\r", "")
        if (extension.lowercase() in setOf(".ass", ".ssa")) {
            var format = listOf("Layer", "Start", "End", "Style", "Name", "MarginL", "MarginR", "MarginV", "Effect", "Text")
            var events = false
            val result = mutableListOf<Cue>()
            for (line in text.lines()) {
                if (line.startsWith("[")) events = line.startsWith("[Events]", ignoreCase = true)
                if (!events) continue
                if (line.startsWith("Format:", ignoreCase = true)) {
                    format = line.substringAfter(':').split(',').map(String::trim)
                }
                if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
                val fields = line.substringAfter(':').trimStart().split(',')
                fun index(name: String) = format.indexOfFirst { it.equals(name, ignoreCase = true) }
                val start = clock(fields.getOrNull(index("Start")) ?: "")
                val end = clock(fields.getOrNull(index("End")) ?: "")
                val textIndex = index("Text")
                require(textIndex >= 0) { "Subtitle format has no Text field." }
                result.add(Cue(start, end, fields.drop(textIndex).joinToString(",")))
            }
            return result
        }
        val timing = Regex("(\\d+:\\d{2}:\\d{2}[,.]\\d+)\\s*-->\\s*(\\d+:\\d{2}:\\d{2}[,.]\\d+)")
        return text.split(Regex("\\n\\s*\\n")).mapNotNull { block ->
            val lines = block.lines()
            val index = lines.indexOfFirst { "-->" in it }
            if (index < 0) return@mapNotNull null
            val match = timing.find(lines[index]) ?: return@mapNotNull null
            Cue(clock(match.groupValues[1]), clock(match.groupValues[2]), lines.drop(index + 1).joinToString("\n"))
        }
    }

    fun valid(cues: List<Cue>): Boolean = cues.isNotEmpty() && cues.all {
        it.start.isFinite() && it.end.isFinite() && it.start >= 0 && it.end > it.start
    }

    fun overlap(cues: List<Cue>, speech: List<Cue>): Overlap {
        val merged = mutableListOf<Cue>()
        for (cue in speech.sortedBy { it.start }) {
            val last = merged.lastOrNull()
            if (last != null && cue.start <= last.end) {
                merged[merged.lastIndex] = last.copy(end = max(last.end, cue.end))
            } else merged.add(cue)
        }
        var total = 0.0
        var matched = 0.0
        var hits = 0
        for (cue in cues) {
            val duration = cue.end - cue.start
            var amount = 0.0
            var low = 0
            var high = merged.size
            while (low < high) {
                val mid = (low + high) / 2
                if (merged[mid].end <= cue.start) low = mid + 1 else high = mid
            }
            for (i in low until merged.size) {
                val ref = merged[i]
                if (ref.start >= cue.end) break
                amount += max(0.0, min(cue.end, ref.end) - max(cue.start, ref.start))
            }
            total += duration
            matched += amount
            if (amount / duration >= 0.2) hits++
        }
        return Overlap(if (total > 0) matched / total else 0.0, if (cues.isNotEmpty()) hits.toDouble() / cues.size else 0.0)
    }

    fun assess(before: List<Cue>, after: List<Cue>, reference: List<Cue>, duration: Double = Double.POSITIVE_INFINITY): Assessment {
        require(valid(before) && valid(after) && valid(reference)) { "No usable subtitle or speech timings were found." }
        require(before.size == after.size && before.indices.all { before[it].text.trim() == after[it].text.trim() }) {
            "The result changed subtitle content. The original must be kept."
        }
        val old = overlap(before, reference)
        val next = overlap(after, reference)
        val shifts = before.indices.map { after[it].start - before[it].start }
        val sections = 1 + shifts.zipWithNext().count { (a, b) -> abs(b - a) > 0.4 }
        val reliable = after.size >= 8 && next.score >= 0.45 && next.coverage >= 0.65 &&
            next.score >= old.score - 0.03 && shifts.maxOf { abs(it) } <= 600 &&
            after.all { it.end <= duration + 15 }
        return Assessment(after.size, (old.score * 100).roundToInt(), (next.score * 100).roundToInt(),
            (shifts.sorted()[shifts.size / 2] * 100).roundToInt() / 100.0, sections, reliable)
    }
}
