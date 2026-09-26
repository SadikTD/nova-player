package com.sadik.novaplayer

import android.net.Uri
import com.sadik.novaplayer.core.MpvNative
import com.sadik.novaplayer.core.SubtitleTiming
import com.sadik.novaplayer.core.SubtitleTiming.Cue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.nio.charset.Charset
import kotlin.math.*

/**
 * [status]: idle · listening · aligning · applied · review · error · restored · cancelled
 * (desktop's sync states). [message] is plain text for the sync panel.
 */
data class SyncState(val status:String="idle",val message:String="",val result:File?=null,val video:String="",val shift:Double=0.0,val mode:String=""){
    val running get()=status=="listening"||status=="aligning"
}
object SubtitleJobs {
    /** Bumped whenever the aligner changes: fixes made by an older one are redone, not reused. */
    const val VERSION="3"
    /** Desktop's names and help text for the three methods. */
    fun methodName(mode:String)=when(mode){"gentle"->"Smooth";"offset"->"Simple shift";else->"Best match"}
    fun methodHelp(mode:String)=when(mode){
        "gentle"->"Makes fewer, gentler changes. Try it when Best match jumps around."
        "offset"->"Moves every subtitle by the same amount. Best when all lines are equally early or late."
        else->"Fixes timing that drifts or jumps after scene cuts and ad breaks. Recommended."
    }
    fun shiftText(shift:Double)=abs(shift).let{if(it<.15)"Timing was already close" else "Moved about ${if(it<10)"%.1f".format(it) else it.roundToInt().toString()} s ${if(shift>0)"later" else "earlier"}"}

    /** "Show.S01E01.en.srt" for the file the user knows, even when the source is a stored copy. */
    private fun originalName(f: File)=f.nameWithoutExtension.removeSuffix(".synced")
    val state=MutableStateFlow(SyncState())
    private var job:Job?=null

    /** A synced copy Nova made (lives in a "sync-…" folder next to its version and source notes). */
    fun isFix(path:String)=path.isNotBlank()&&File(path).parentFile?.name?.startsWith("sync-")==true
    /** Made by an older aligner: replaced by a fresh sync instead of being shown again. */
    fun outdated(path:String)=isFix(path)&&runCatching{File("$path.version").readText().trim()}.getOrNull()!=VERSION
    /** The subtitle a fix was made from, and the video it was made for. */
    fun fixSource(path:String)=runCatching{File(File(path).parentFile,"source.txt").readLines()}.getOrNull()?.let{it.getOrNull(0).orEmpty() to it.getOrNull(1).orEmpty()}

    /**
     * Subtitle text exactly as stored. Every byte except the timing lines must survive a sync,
     * and downloads come in every encoding (cp1252 accents, Bengali UTF-8…): one byte per char
     * round-trips them all. UTF-16 is the exception; it is read as such and saved as UTF-8.
     */
    internal fun readSubtitle(file:File):Pair<String,Charset>{
        val b=file.readBytes()
        val utf16=b.size>=2&&((b[0]==0xFF.toByte()&&b[1]==0xFE.toByte())||(b[0]==0xFE.toByte()&&b[1]==0xFF.toByte()))
        return if(utf16)String(b,Charsets.UTF_16) to Charsets.UTF_8 else String(b,Charsets.ISO_8859_1) to Charsets.ISO_8859_1
    }

    fun cancel(){
        job?.cancel();runCatching{MpvNative.cancelAnalysis()}
        if(state.value.running)state.value=state.value.copy(status="cancelled",message="Sync stopped. Your subtitles were not changed.",result=null)
    }
    /** Forget the panel state for the current video (after its subtitles are removed). */
    fun reset(){cancel();state.value=SyncState()}

    fun apply(message:String="Subtitles synced to the dialogue."){
        val s=state.value
        if(s.video!=NovaRuntime.state.value.video?.uri)return
        val file=s.result?:return
        if(!NovaRuntime.attachSubtitle(file,false)){state.value=s.copy(status="error",message="The fixed subtitle couldn't be loaded. Your original is unchanged.",result=null);return}
        // A baked correction replaces any manual nudging (desktop behaviour).
        NovaRuntime.property("sub-delay",0.0);NovaRuntime.property("sub-speed",1.0)
        File(file.path+".approved").writeText("approved")
        state.value=s.copy(status="applied",message=message,result=null)
        NovaRuntime.notice.value="Subtitles synced · "+shiftText(s.shift).replaceFirstChar{it.lowercase()}
    }
    /** "Keep original" on an uncertain result. */
    fun dismiss(){val s=state.value;if(s.status=="review")state.value=s.copy(status="idle",message="Kept the original timing.",result=null)}
    /** Undo: back to the subtitle the fix was made from, and never bring this fix back by itself. */
    fun undo(){
        val video=NovaRuntime.state.value.video?:return
        if(isFix(video.externalSub))File(video.externalSub+".approved").delete()
        NovaRuntime.restoreSubtitle()
        state.value=SyncState("restored","Back to the original timing. Nova will not reapply this fix.",video=video.uri,mode=state.value.mode)
    }

    /** [manual] = the user pressed a sync button: a reliable result is applied the moment it is ready. */
    fun start(mode:String,reference:File?=null,manual:Boolean=false){
        if(job?.isActive==true)return
        val video=NovaRuntime.state.value.video?:return
        fun fail(message:String){state.value=SyncState("error",message,video=video.uri,mode=mode)}
        val source=File(video.originalSub.ifBlank{video.externalSub})
        if(video.originalSub.isBlank()&&video.externalSub.isBlank()||!source.isFile)return fail("Add a subtitle file first: find one online or open one. Subtitles built into the video can't be synced yet.")
        if(source.extension.lowercase() !in setOf("srt","ass","ssa"))return fail("Sync works with SRT, ASS and SSA subtitles. This one is ${source.extension.uppercase()}.")
        val uri=Uri.parse(video.uri)
        if(reference==null&&uri.scheme !in setOf("file","content"))return fail("Sync works for videos stored on your phone. For a stream, use \"Match another subtitle file\".")
        // "no" = the viewer turned the sound off; the dialogue is still in the default track.
        val aid=NovaRuntime.engine.getStr("aid")?.takeIf{it!="no"}?:"auto"
        val prior=job
        job=NovaRuntime.scope.launch{
            prior?.join()
            state.value=SyncState(if(reference!=null)"aligning" else "listening",if(reference!=null)"Reading the other subtitle file…" else "Listening to the dialogue. You can keep watching.",video=video.uri,mode=mode)
            val pcm=File(NovaRuntime.app.cacheDir,"sync-${System.nanoTime()}.pcm")
            var shift=0.0
            try {
                val result=withContext(Dispatchers.IO){
                    val (text,charset)=readSubtitle(source)
                    // A mangled cue (e.g. 00:00:00 → 00:32:19) reads as half an hour of dialogue and ruins alignment.
                    val original=if(source.extension.equals("srt",true))SubtitleTiming.repairSrt(text) else text
                    val before=SubtitleTiming.cues(original,".${source.extension}")
                    require(SubtitleTiming.valid(before)&&before.size<=30000){"This subtitle file looks damaged: its timings can't be read."}
                    val key=stableId(video.uri+video.size+source.readBytes().contentHashCode()+mode+aid+"align-v$VERSION"+(reference?.readText()?:"audio"))
                    val output=NovaRuntime.subtitleFile("sync-$key","${originalName(source)}.synced.${source.extension}")
                    if(NovaRuntime.pref("subSyncReuse",true)&&output.isFile&&File(output.path+".approved").isFile&&!outdated(output.path))
                        return@withContext Triple(output,"Using the subtitle fix saved for this video.",true)
                    val speech=if(reference!=null) SubtitleTiming.cues(reference.readText(),".${reference.extension}") else {
                        val fd=if(uri.scheme=="content")runCatching{NovaRuntime.app.contentResolver.openFileDescriptor(uri,"r")}.getOrNull()
                            ?:throw IllegalStateException("Nova can't open this video's file any more. Open it again from the library and retry.") else null
                        try{
                            val path=if(fd!=null)"fd://${fd.fd}" else uri.path!!
                            val code=MpvNative.extractAudio(path,aid,pcm.path);ensureActive()
                            require(code>=0&&pcm.length()>0){"Nova couldn't read this video's sound. Try another audio track, or use \"Match another subtitle file\"."}
                            MpvNative.detectSpeech(pcm.path).toList().chunked(2).map{Cue(it[0],it[1])}
                        }finally{fd?.close()}
                    }
                    if(BuildConfig.DEBUG)android.util.Log.d("NovaSync","speech ${speech.size}: "+speech.take(10).joinToString{"%.2f-%.2f".format(it.start,it.end)}+" | cues: "+before.take(6).joinToString{"%.2f-%.2f".format(it.start,it.end)})
                    ensureActive()
                    require(SubtitleTiming.valid(speech)&&speech.size>=3){if(reference!=null)"The other subtitle file has no usable timings." else "Not enough speech was found in this video. Try another audio track, or use \"Match another subtitle file\"."}
                    state.value=state.value.copy(status="aligning",message="Lining the subtitles up with the dialogue…")
                    // A reference subtitle's lines are the target itself; speech needs the studio lead.
                    val aligned=SubtitleAlign.fit(before,speech,mode,lead=if(reference!=null)0.0 else SubtitleAlign.LEAD){ensureActive()}
                    val corrected=aligned.cues
                    // Overlap levels depend on how much of the audio the detector calls speech, so trust
                    // comes from the parts of the file agreeing on one timing (SubtitleAlign.Aligned).
                    val assessment=SubtitleTiming.assess(before,corrected,speech,video.duration.takeIf{it>0}?:Double.POSITIVE_INFINITY,minScore=0.0,minCoverage=0.0)
                        .let{it.copy(reliable=it.reliable&&aligned.confidence>=SubtitleAlign.CONFIDENT)}
                    if(BuildConfig.DEBUG)android.util.Log.d("NovaSync","ratio ${aligned.ratio} confidence ${aligned.confidence} ${assessment}")
                    output.parentFile?.mkdirs()
                    output.writeBytes(SubtitleAlign.rewrite(original,source.extension,corrected).toByteArray(charset))
                    File(output.path+".version").writeText(VERSION)
                    File(output.parentFile,"source.txt").writeText(source.path+"\n"+video.uri)
                    shift=assessment.medianShift
                    Triple(output,if(assessment.reliable)"A fix is ready." else "Nova found a new timing but isn't sure it's right. Try it and see.",assessment.reliable)
                }
                ensureActive()
                if(NovaRuntime.state.value.video?.uri==video.uri){
                    state.value=SyncState("review",result.second,result.first,video.uri,shift,mode)
                    // The whole point of syncing: once it's ready and trustworthy, just use it.
                    if(result.third&&(manual||NovaRuntime.pref("subSyncAutoApply",true)))apply(if(result.second.startsWith("Using"))result.second else "Subtitles synced to the dialogue.")
                    else if(!result.third)NovaRuntime.snack.value=Snack("Nova isn't sure about this subtitle fix","Try it",run={apply()})
                }
            }catch(e:CancellationException){throw e}catch(e:Exception){
                if(NovaRuntime.state.value.video?.uri==video.uri)fail(e.message?.takeIf{it.endsWith(".")||it.endsWith("\".")}?:"Something went wrong while syncing. Your subtitles were not changed.")
            }finally{pcm.delete()}
        }
    }
}
object SubtitleAlign {
    /** Timeline resolution for both the speech map and the offsets tried. */
    private const val STEP=.05
    /** Speed ratios between common frame rates (23.976, 24, 25). A subtitle made for another
     *  release drifts steadily — 24 vs 23.976 fps is 0.3 s every 5 minutes — which no single
     *  shift can fix. 1.0 must stay first: it wins ties. */
    private val RATIOS=doubleArrayOf(1.0,24/23.976,23.976/24,25/24.0,24/25.0,25/23.976,23.976/25)
    /** How much better (seconds of speech matched) a new section must fit before the timing
     *  may jump there. Smart jumps at ad breaks and trimmed scenes; Smooth only at clear ones.
     *  Tuned on real episodes against desktop alass results (SyncBenchTest): much lower and
     *  lines chase noise in the speech map, much higher and ad-break jumps are missed. */
    fun penalty(mode:String)=if(mode=="gentle")GENTLE_PENALTY else 5.5
    const val GENTLE_PENALTY=9.0
    /** Extra cost per second a jump moves the timing, so a jump across the room needs far
     *  more evidence than a one-second ad-break correction. */
    fun jumpCost(mode:String)=if(mode=="gentle")GENTLE_JUMP else 1.0
    const val GENTLE_JUMP=1.5

    /**
     * Where each cue fits the detected speech, the way alass does it: find the best overall
     * speed and shift, then let the timing change between lines only where the match gains
     * more than [splitPenalty] (dynamic programming over every line and every offset).
     * Lines never swap order, and text is never touched.
     */
    fun align(cues:List<Cue>,reference:List<Cue>,mode:String,splitPenalty:Double=penalty(mode),jumpCost:Double=jumpCost(mode),check:()->Unit={})=
        fit(cues,reference,mode,splitPenalty,jumpCost,check=check).cues

    /** [confidence]: share of the file's parts that, searched on their own within a minute,
     *  land on the same timing as the whole. Parts of a subtitle for different audio scatter. */
    data class Aligned(val cues:List<Cue>,val confidence:Double,val ratio:Double)
    /** Below this the match is a guess. Measured on real episodes: another episode's subtitle
     *  scores 0-0.25, genuine matches 0.6 and up. */
    const val CONFIDENT=.5

    /** [lead]: how far ahead of a speech onset a line should appear (0 when [reference] is
     *  another subtitle rather than detected speech). */
    fun fit(cues:List<Cue>,reference:List<Cue>,mode:String,splitPenalty:Double=penalty(mode),jumpCost:Double=jumpCost(mode),lead:Double=LEAD,check:()->Unit={}):Aligned{
        val speech=SpeechMap(reference,lead)
        val (ratio,offset,confidence)=overall(cues,speech,if(mode=="offset")doubleArrayOf(1.0) else RATIOS,check)
        val starts=DoubleArray(cues.size){cues[it].start*ratio+offset}
        val ends=DoubleArray(cues.size){cues[it].end*ratio+offset}
        fun result(shift:(Int)->Double)=Aligned(cues.mapIndexed{i,c->val s=max(0.0,starts[i]+shift(i));c.copy(start=s,end=max(s+.01,ends[i]+shift(i)))},confidence,ratio)
        if(mode=="offset"||cues.size<24)return result{0.0}
        // Offsets tried around the overall fit: states k = 0..2K, offset (k-K)*STEP.
        val n=cues.size
        val k0=min(((if(mode=="gentle")20.0 else 60.0)/STEP).toInt(),(12_000_000/n-1)/2).coerceAtLeast(20)
        val states=2*k0+1
        val from=ShortArray(n*states)
        fun origin(i:Int)=(starts[i]/STEP).roundToInt()-k0
        fun width(i:Int)=max(1,((ends[i]-starts[i])/STEP).roundToInt())
        // A jump costs splitPenalty plus jumpCost per second moved: real jumps (ad breaks,
        // a trimmed scene) are mostly small, and far jumps are where noisy speech misleads.
        val perBin=jumpCost*STEP
        var prev=DoubleArray(states){k->speech.cue(origin(0)+k,width(0))-if(k==k0)0.0 else splitPenalty+perBin*abs(k-k0)}
        var cur=DoubleArray(states)
        val left=DoubleArray(states);val leftAt=IntArray(states)
        val window=IntArray(states)
        for(i in 1 until n){
            check()
            // left[k] = best prev[k'] + perBin*k' over k' <= k (jumping later: always in order)
            for(k in 0 until states){val v=prev[k]+perBin*k;if(k==0||v>left[k-1]){left[k]=v;leftAt[k]=k}else{left[k]=left[k-1];leftAt[k]=leftAt[k-1]}}
            // Jumping earlier may not pass the previous line: k' <= k+gap.
            val gap=floor((starts[i]-starts[i-1])/STEP).toInt()
            val a=origin(i);val w=width(i)
            var head=0;var tail=0;var added=states
            for(k in states-1 downTo 0){
                var value=prev[k];var source=k
                val j=min(k,k+gap)
                if(j>=0){val v=left[j]-perBin*k-splitPenalty;if(v>value){value=v;source=leftAt[j]}}
                if(gap>0){
                    // Sliding max of prev[k'] - perBin*k' over k < k' <= k+gap.
                    while(added>k+1){added--;val v=prev[added]-perBin*added;while(tail>head&&prev[window[tail-1]]-perBin*window[tail-1]<=v)tail--;window[tail++]=added}
                    while(tail>head&&window[head]>k+gap)head++
                    if(tail>head){val m=window[head];val v=prev[m]-perBin*(m-k)-splitPenalty;if(v>value){value=v;source=m}}
                }
                cur[k]=value+speech.cue(a+k,w)
                from[i*states+k]=source.toShort()
            }
            val t=prev;prev=cur;cur=t
        }
        val chosen=IntArray(n);chosen[n-1]=prev.indices.maxBy{prev[it]}
        for(i in n-1 downTo 1)chosen[i-1]=from[i*states+chosen[i]].toInt()
        return result{(chosen[it]-k0)*STEP}
    }

    /** Best single speed ratio and shift (up to 10 minutes either way), and the confidence
     *  described at [Aligned]. Another speed must clearly beat 1.0 to be used. */
    internal fun overall(cues:List<Cue>,speech:SpeechMap,ratios:DoubleArray,check:()->Unit):Triple<Double,Double,Double>{
        val sample=if(cues.size>400)cues.filterIndexed{i,_->i%(cues.size/400+1)==0}else cues
        fun rate(list:List<Cue>,ratio:Double,offset:Double)=list.sumOf{speech.rate(it.start*ratio+offset,it.end*ratio+offset)}-abs(offset)*1e-6
        fun search(list:List<Cue>,ratio:Double,center:Double=0.0,radius:Int=3000):Double{
            var best=center;var quality=Double.NEGATIVE_INFINITY
            for(i in -radius..radius){check();val o=center+i*.2;val q=rate(list,ratio,o);if(q>quality){quality=q;best=o}}
            return best
        }
        var ratio=1.0;var offset=0.0;var plain=0.0;var top=Double.NEGATIVE_INFINITY
        for(r in ratios){
            // Coarse scan of every shift on a sample, then refine the winner on all lines.
            val coarse=search(sample,r)
            var o=coarse;var quality=rate(cues,r,o)
            for(k in -6..6){val q=rate(cues,r,coarse+k*STEP);if(q>quality){quality=q;o=coarse+k*STEP}}
            if(r==1.0){plain=quality;top=quality;ratio=r;offset=o}
            else if(quality>top&&quality>plain+max(.15,abs(plain)*.03)){top=quality;ratio=r;offset=o}
        }
        // A few minutes of lines can't pin their timing down across ±10 minutes on their own,
        // but they can within a minute. 2.5 s leaves room for the ad-break jumps fixed later.
        val parts=cues.chunked(max(12,(cues.size+7)/8)).filter{it.size>=12}
        val agree=parts.count{abs(search(it,ratio,offset,300)-offset)<=2.5}
        return Triple(ratio,offset,if(parts.isEmpty())0.0 else agree.toDouble()/parts.size)
    }

    /** Detected speech on a [STEP] grid, centred so a line placed at random scores about zero:
     *  per second, speech counts +(1-p), silence -p (p = share of speech). Past either end counts
     *  as silence. Sums are in seconds. */
    /** Seconds of audio a moment's speech is weighed against (see SpeechMap). 0 = whole video. */
    internal var localWindow=30.0
    /** Onset bonus (seconds of speech) for a line appearing exactly on time; see markOnsets. */
    internal var onsetWeight=1.0
    internal var LEAD=.18
    private const val ONSET_REACH=.3
    internal class SpeechMap(reference:List<Cue>,private val lead:Double=LEAD){
        val size=((reference.maxOf{it.end}+1)/STEP).toInt()
        private val prefix=DoubleArray(size+1)
        private val silence:Double
        /** Bonus for a cue starting at each bin: high [lead] s before speech begins after a pause. */
        private val onset=DoubleArray(size)
        init{
            val bins=BooleanArray(size)
            for(c in reference)for(i in (c.start/STEP).roundToInt().coerceAtLeast(0) until (c.end/STEP).roundToInt().coerceAtMost(size))bins[i]=true
            val p=bins.count{it}.toDouble()/size
            silence=-p*STEP
            if(localWindow<=0)for(i in 0 until size)prefix[i+1]=prefix[i]+STEP*(if(bins[i])1-p else -p)
            else centreLocally(bins)
            markOnsets(reference.sortedBy{it.start})
        }
        // Overlap alone is satisfied anywhere inside a range: subtitles stay up after the voice
        // stops, so a line can show up to half a second early and still cover the same speech.
        // What viewers notice is the moment a line appears, so reward starting where speech
        // starts. Studio subtitles appear 0.05-0.2 s ahead of the voice (measured on Netflix
        // and Disney+ releases). Onsets after a real pause only; detector flicker doesn't count.
        private fun markOnsets(speech:List<Cue>){
            if(onsetWeight<=0)return
            val raw=DoubleArray(size);val reach=(ONSET_REACH/STEP).roundToInt()
            var lastEnd=Double.NEGATIVE_INFINITY
            for(c in speech){
                if(c.start-lastEnd>=.25&&c.end-c.start>=.2){
                    val t=((c.start-lead)/STEP).roundToInt()
                    for(j in -reach..reach)if(t+j in 0 until size)raw[t+j]+=onsetWeight*(1-abs(j).toDouble()/(reach+1))
                }
                lastEnd=max(lastEnd,c.end)
            }
            // Centred on the surrounding stretch like the speech itself, so a burst of onsets in
            // noise doesn't attract lines.
            val sums=DoubleArray(size+1);for(i in 0 until size)sums[i+1]=sums[i]+raw[i]
            val half=(max(localWindow,30.0)/2/STEP).toInt()
            for(i in 0 until size){val lo=max(0,i-half);val hi=min(size,i+half+1);onset[i]=raw[i]-(sums[hi]-sums[lo])/(hi-lo)}
        }
        // Centre on the speech share of the surrounding stretch, not the whole video. In a
        // gunfight the detector calls most of the noise speech; against the global share that
        // reads as dense dialogue and pulls lines seconds away. Locally, every placement there
        // scores about the same, so only real speech/silence contrast moves a line.
        private fun centreLocally(bins:BooleanArray){
            val count=IntArray(size+1);for(i in 0 until size)count[i+1]=count[i]+if(bins[i])1 else 0
            val half=(localWindow/2/STEP).toInt()
            for(i in 0 until size){
                val lo=max(0,i-half);val hi=min(size,i+half+1)
                val local=(count[hi]-count[lo]).toDouble()/(hi-lo)
                prefix[i+1]=prefix[i]+STEP*(if(bins[i])1-local else -local)
            }
        }
        /** Bins [a, b). */
        fun sum(a:Int,b:Int):Double{
            val lo=a.coerceIn(0,size);val hi=b.coerceIn(0,size)
            val inside=max(0,hi-lo)
            return (if(inside>0)prefix[hi]-prefix[lo] else 0.0)+silence*(b-a-inside)
        }
        /** A cue starting at bin [a], [width] bins long: speech it covers, plus the bonus for
         *  appearing just as someone starts talking. */
        fun cue(a:Int,width:Int)=sum(a,a+width)+if(a in 0 until size)onset[a] else 0.0
        fun rate(start:Double,end:Double):Double{val a=(start/STEP).roundToInt();return cue(a,max(1,(end/STEP).roundToInt()-a))}
    }
    fun rewrite(source:String,extension:String,cues:List<Cue>):String{
        fun stamp(seconds:Double,ass:Boolean):String{val units=if(ass)100 else 1000;val n=(seconds*units).roundToLong().coerceAtLeast(0);return if(ass)"%d:%02d:%02d.%02d".format(n/units/3600,n/units/60%60,n/units%60,n%units)else "%02d:%02d:%02d,%03d".format(n/units/3600,n/units/60%60,n/units%60,n%units)}
        var index=0
        if(extension.lowercase() !in setOf("ass","ssa"))return Regex("\\d+:\\d{2}:\\d{2}[,.]\\d+\\s*-->\\s*\\d+:\\d{2}:\\d{2}[,.]\\d+").replace(source){val c=cues[index++];"${stamp(c.start,false)} --> ${stamp(c.end,false)}"}
        var fields=listOf("Layer","Start","End","Style","Name","MarginL","MarginR","MarginV","Effect","Text");var events=false
        return source.lines().joinToString("\n"){line->if(line.startsWith("["))events=line.startsWith("[Events]",true);if(events&&line.startsWith("Format:",true))fields=line.substringAfter(':').split(',').map{it.trim()};if(events&&line.startsWith("Dialogue:",true)){val row=line.substringAfter(':').trimStart().split(',',limit=fields.size).toMutableList();val c=cues[index++];row[fields.indexOfFirst{it.equals("Start",true)}]=stamp(c.start,true);row[fields.indexOfFirst{it.equals("End",true)}]=stamp(c.end,true);"Dialogue: "+row.joinToString(",")}else line}
    }
}


