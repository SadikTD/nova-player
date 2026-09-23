package com.sadik.novaplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanSrtTest {
    @Test fun stackedAdTimingLineIsCutAwayFromRealDialogue() {
        // The injector overwrote cue 1's index: two timing lines, no blank line between them.
        val raw = "00:00:01,000 --> 00:00:03,000\r\nSupport us and become VIP member\r\n00:00:04,000 --> 00:00:06,000\r\nHello there.\r\n\r\n3\r\n00:00:07,000 --> 00:00:09,000\r\nVisit osdb.link now\r\n"
        val out = cleanSrt(raw)
        assertFalse(out.contains("VIP")); assertFalse(out.contains("osdb.link")); assertFalse(out.contains('\r'))
        assertEquals("1\n00:00:04,000 --> 00:00:06,000\nHello there.\n", out)
    }
    @Test fun untimedTextPassesThroughUntouched() { assertEquals("just text", cleanSrt("just text")) }
    @Test fun adDetectionIgnoresTags() { assertTrue(looksLikeAd("<i>www.example.com</i>")); assertFalse(looksLikeAd("<i>See you tomorrow.</i>")) }
}

class SrtRepairTest {
    @Test fun arrowWithoutSpacesIsRepaired() {
        // Real OpenSubtitles upload for The Mentalist S07E13: mpv refused it ("Can not open external file").
        assertEquals("00:00:01,436 --> 00:00:02,915", canonicalTiming("00:00:01,436--> 00:00:02,915"))
        assertEquals("00:00:01,500 --> 01:02:03,040", canonicalTiming("0:0:1.5-->1:2:3,04"))
    }
    @Test fun cleanSrtEmitsCanonicalTiming() {
        assertEquals("1\n00:00:01,436 --> 00:00:02,915\nPreviously\n", cleanSrt("1\r\n00:00:01,436--> 00:00:02,915\r\nPreviously\r\n"))
    }
    @Test fun healKeepsForeignBytesAndLineEndings() {
        val cp1252 = byteArrayOf(0x31, 0x0D, 0x0A) + "00:00:01,000-->00:00:02,000".toByteArray() + byteArrayOf(0x0D, 0x0A, 0x43, 0x61, 0x66, 0xE9.toByte(), 0x0D, 0x0A)
        val healed = healSrtBytes(cp1252)!!
        assertEquals("1\r\n00:00:01,000 --> 00:00:02,000\r\nCaf", String(healed, Charsets.ISO_8859_1).dropLast(3))
        assertEquals(0xE9.toByte(), healed[healed.size - 3]) // é byte untouched
        assertEquals(null, healSrtBytes("1\n00:00:01,000 --> 00:00:02,000\nOk\n".toByteArray()))
    }
}
