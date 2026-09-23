package com.sadik.novaplayer
import org.junit.Assert.*
import org.junit.Test
import com.sadik.novaplayer.core.SubtitleTiming
class SubtitleAlignTest {
    @Test fun findsKnownOffsetWithoutChangingDialogue(){val ref=(0..40).map{SubtitleTiming.Cue(it*7.3+20,it*7.3+22.1,"Line $it")};val late=ref.map{it.copy(start=it.start+4.2,end=it.end+4.2)};val result=SubtitleAlign.align(late,ref,"offset");assertEquals(ref[20].start,result[20].start,.15);assertEquals(late.map{it.text},result.map{it.text})}
    @Test fun preservesAssStyleAndCommas(){val input="[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:10.00,0:00:12.00,Default,,0,0,0,,Hello, world";val cues=SubtitleTiming.cues(input,".ass").map{it.copy(start=it.start+2,end=it.end+2)};val result=SubtitleAlign.rewrite(input,"ass",cues);assertTrue(result.contains("0:00:12.00,0:00:14.00,Default,,0,0,0,,Hello, world"))}
    @Test fun preservesSrtTextAndFormatting(){val input="1\n00:00:10,000 --> 00:00:12,000\n<i>Hello!</i>\n";val result=SubtitleAlign.rewrite(input,"srt",listOf(SubtitleTiming.Cue(11.25,13.25,"<i>Hello!</i>")));assertTrue(result.contains("00:00:11,250 --> 00:00:13,250"));assertTrue(result.endsWith("<i>Hello!</i>\n"))}
}
