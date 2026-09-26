package com.sadik.novaplayer
import org.junit.Assert.*
import org.junit.Test
import com.sadik.novaplayer.core.SubtitleTiming
class SubtitleAlignTest {
    @Test fun findsKnownOffsetWithoutChangingDialogue(){val ref=(0..40).map{SubtitleTiming.Cue(it*7.3+20,it*7.3+22.1,"Line $it")};val late=ref.map{it.copy(start=it.start+4.2,end=it.end+4.2)};val result=SubtitleAlign.align(late,ref,"offset");assertEquals(ref[20].start,result[20].start,.15);assertEquals(late.map{it.text},result.map{it.text})}
    @Test fun preservesAssStyleAndCommas(){val input="[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:10.00,0:00:12.00,Default,,0,0,0,,Hello, world";val cues=SubtitleTiming.cues(input,".ass").map{it.copy(start=it.start+2,end=it.end+2)};val result=SubtitleAlign.rewrite(input,"ass",cues);assertTrue(result.contains("0:00:12.00,0:00:14.00,Default,,0,0,0,,Hello, world"))}
    /** 40 minutes of speech-like intervals; the subtitle is the same lines, so the truth is known. */
    private fun dialogue(seed:Int)=java.util.Random(seed.toLong()).let{r->var t=3.0;List(700){i->val s=t+.3+r.nextDouble()*4;val e=s+.6+r.nextDouble()*3;t=e;SubtitleTiming.Cue(s,e,"Line $i")}}
    private fun within(result:List<SubtitleTiming.Cue>,truth:List<SubtitleTiming.Cue>,limit:Double)=result.indices.count{kotlin.math.abs(result[it].start-truth[it].start)<=limit}.toDouble()/truth.size

    @Test fun fixesFrameRateDriftAndAdBreakJump(){
        val speech=dialogue(1)
        // Made for 25 fps, then 1.5 s late from the middle on (an ad break cut differently).
        val sub=speech.mapIndexed{i,c->val f=25/23.976;val late=if(i>=350)1.5 else 0.0;c.copy(start=c.start*f+late,end=c.end*f+late)}
        val fit=SubtitleAlign.fit(sub,speech,"smart")
        assertTrue("confidence ${fit.confidence}",fit.confidence>=SubtitleAlign.CONFIDENT)
        assertTrue(within(fit.cues,speech,.15)>.95)
        assertEquals(sub.map{it.text},fit.cues.map{it.text})
    }
    @Test fun leavesSyncedSubtitleAlone(){
        val speech=dialogue(2)
        for(mode in listOf("smart","gentle","offset"))assertEquals(mode,1.0,within(SubtitleAlign.align(speech,speech,mode),speech,.051),0.0)
    }
    @Test fun subtitleForOtherAudioIsNotTrusted(){
        for(mode in listOf("smart","offset"))assertTrue(mode,SubtitleAlign.fit(dialogue(3),dialogue(4),mode).confidence<SubtitleAlign.CONFIDENT)
    }
    @Test fun repairsHalfHourCueOnly(){
        val input="1\n00:00:00,000 --> 00:32:19,040\nHello there\n\n2\n00:00:05,000 --> 00:00:07,000\nSecond\n\n3\n00:00:09,000 --> 00:00:10,500\nThird\n"
        val fixed=SubtitleTiming.repairSrt(input);val cues=SubtitleTiming.cues(fixed)
        assertTrue(cues[0].end<=5.0);assertTrue(fixed.contains("00:00:05,000 --> 00:00:07,000\nSecond"));assertEquals(3,cues.size)
        val clean="1\n00:00:01,000 --> 00:00:02,000\nFine  \n"
        assertSame(clean,SubtitleTiming.repairSrt(clean))
    }
    @Test fun preservesSrtTextAndFormatting(){val input="1\n00:00:10,000 --> 00:00:12,000\n<i>Hello!</i>\n";val result=SubtitleAlign.rewrite(input,"srt",listOf(SubtitleTiming.Cue(11.25,13.25,"<i>Hello!</i>")));assertTrue(result.contains("00:00:11,250 --> 00:00:13,250"));assertTrue(result.endsWith("<i>Hello!</i>\n"))}
}
