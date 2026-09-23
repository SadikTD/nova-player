package com.sadik.novaplayer.core

import org.junit.Assert.*
import org.junit.Test

class SubtitleTimingTest {
    @Test fun overlappingSpeechIsNotCountedTwice() {
        val result = SubtitleTiming.overlap(listOf(SubtitleTiming.Cue(0.0, 10.0)),
            listOf(SubtitleTiming.Cue(0.0, 7.0), SubtitleTiming.Cue(3.0, 10.0)))
        assertEquals(1.0, result.score, 0.00001)
    }

    @Test fun assPreservesCommasAndStyleTags() {
        val source = "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:01.00,0:00:02.50,Default,,0,0,0,,{\\i1}Hello, world"
        val cue = SubtitleTiming.cues(source, ".ass").single()
        assertEquals("{\\i1}Hello, world", cue.text)
        assertEquals(2.5, cue.end, 0.00001)
    }

    @Test fun srtAcceptsBomAndWindowsLineEndings() {
        val source = "\uFEFF1\r\n00:00:01,000 --> 00:00:02,000\r\nHello\r\n"
        val cues = SubtitleTiming.cues(source)
        assertTrue(SubtitleTiming.valid(cues))
        assertEquals("Hello", cues.single().text.trim())
    }

    @Test(expected = IllegalArgumentException::class)
    fun changedDialogueCannotBeApproved() {
        SubtitleTiming.assess(listOf(SubtitleTiming.Cue(0.0, 1.0, "Original")),
            listOf(SubtitleTiming.Cue(1.0, 2.0, "Changed")), listOf(SubtitleTiming.Cue(1.0, 2.0)))
    }

    @Test fun uncertainShortResultsRequireReview() {
        val cues = listOf(SubtitleTiming.Cue(0.0, 1.0, "Hello"))
        assertFalse(SubtitleTiming.assess(cues, cues, cues).reliable)
    }

    @Test fun consistentCorrectionPassesTimingChecks() {
        val before = (0..7).map { SubtitleTiming.Cue(it * 10.0, it * 10.0 + 2, "Line $it") }
        val after = before.map { it.copy(start = it.start + 4, end = it.end + 4) }
        val result = SubtitleTiming.assess(before, after, after, 100.0)
        assertTrue(result.reliable)
        assertEquals(4.0, result.medianShift, 0.00001)
        assertEquals(100, result.after)
    }
}
